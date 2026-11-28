package com.mastersoft.velocast.launcher.adb

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbUsbTransportFramingTest {

    @Test
    fun `writes header and payload as separate bulk transfers and appends ZLP on maxPacketSize`() {
        val fake = RecordingUsbBulkConnection(outMaxPacketSize = 512)
        val transport = AdbUsbTransport(fake)

        // 1. Empty payload -> single 24-byte header transfer, no payload transfer, no ZLP.
        transport.writePacket(AdbPacket(AdbPacket.A_OKAY, 1, 2), skipChecksum = true)
        assertEquals(listOf(24), fake.outboundSizes())
        fake.clearOutbound()

        // 2. Non-multiple payload (511 bytes) -> 24-byte header + 511-byte payload, no ZLP.
        val payload511 = ByteArray(511) { idx -> (idx and 0xFF).toByte() }
        transport.writePacket(AdbPacket(AdbPacket.A_WRTE, 1, 2, payload511), skipChecksum = false)
        assertEquals(listOf(24, 511), fake.outboundSizes())
        assertArrayEquals(payload511, fake.outboundPayloads[1])
        val decodedHeader = AdbPacket.decodeHeader(fake.outboundPayloads[0])
        assertEquals(AdbPacket.computeChecksum(payload511), decodedHeader.checksum)
        fake.clearOutbound()

        // 3. Exact 512-byte multiple -> 24-byte header + 512-byte payload + 0-byte ZLP!
        val payload512 = ByteArray(512) { idx -> ((idx * 7) and 0xFF).toByte() }
        transport.writePacket(AdbPacket(AdbPacket.A_WRTE, 1, 2, payload512), skipChecksum = true)
        assertEquals(listOf(24, 512, 0), fake.outboundSizes())
        fake.clearOutbound()

        // 4. Non-power-of-two SuperSpeed burst maxPacketSize (5120 bytes) -> ZLP still appended!
        val burstFake = RecordingUsbBulkConnection(outMaxPacketSize = 5120)
        val burstTransport = AdbUsbTransport(burstFake)
        burstTransport.writePacket(
            AdbPacket(AdbPacket.A_WRTE, 3, 4, ByteArray(5120) { 0x42 }),
            skipChecksum = true
        )
        assertEquals(listOf(24, 5120, 0), burstFake.outboundSizes())
        transport.close()
        burstTransport.close()
    }

    @Test
    fun `chunks large bulk payloads into 16KB USB transfers for API 26-27 compatibility`() {
        val fake = RecordingUsbBulkConnection(outMaxPacketSize = 512)
        val transport = AdbUsbTransport(
            connection = fake,
            maxBulkTransferBytes = AdbUsbConstants.DEFAULT_MAX_BULK_TRANSFER_BYTES
        )

        // 40,000 bytes = 16,384 + 16,384 + 7,232 (not a multiple of 512 -> no trailing ZLP).
        val payload40k = ByteArray(40_000) { idx -> ((idx * 13 + 5) and 0xFF).toByte() }
        transport.writePacket(AdbPacket(AdbPacket.A_WRTE, 10, 20, payload40k), skipChecksum = true)
        assertEquals(listOf(24, 16_384, 16_384, 7_232), fake.outboundSizes())
        val reassembled = fake.outboundPayloads.drop(1).fold(ByteArray(0)) { acc, b -> acc + b }
        assertArrayEquals(payload40k, reassembled)
        fake.clearOutbound()

        // 32,768 bytes = 16,384 + 16,384 (exact multiple of 512 -> appends 0-byte ZLP!).
        val payload32k = ByteArray(32_768) { idx -> (idx and 0xFF).toByte() }
        transport.writePacket(AdbPacket(AdbPacket.A_WRTE, 10, 20, payload32k), skipChecksum = true)
        assertEquals(listOf(24, 16_384, 16_384, 0), fake.outboundSizes())
        transport.close()
    }

    @Test
    fun `reads multi-chunk payloads and skips leading inbound ZLPs between packets`() {
        val fake = RecordingUsbBulkConnection(inMaxPacketSize = 512)
        val transport = AdbUsbTransport(fake)

        // Packet 1: A_CNXN negotiating A_VERSION_SKIP_CHECKSUM.
        val banner = "device::ro.product.model=Pixel6\u0000".toByteArray(Charsets.UTF_8)
        val cnxn = AdbPacket(
            AdbPacket.A_CNXN,
            AdbPacket.A_VERSION_SKIP_CHECKSUM,
            AdbPacket.DEFAULT_MAX_DATA,
            banner
        )
        fake.enqueueInboundPacket(cnxn, skipChecksum = false)

        // Simulate adbd sending a trailing 0-byte ZLP before Packet 2's header, then a 40KB WRTE.
        fake.enqueueInboundRawChunk(ByteArray(0))
        val largePayload = ByteArray(40_000) { idx -> ((idx * 29 + 3) and 0xFF).toByte() }
        fake.enqueueInboundRawChunk(
            AdbPacket(AdbPacket.A_WRTE, 101, 1, largePayload).encodeHeader(skipChecksum = true)
        )
        fake.enqueueInboundRawChunk(largePayload.copyOfRange(0, 16_384))
        fake.enqueueInboundRawChunk(largePayload.copyOfRange(16_384, 32_768))
        fake.enqueueInboundRawChunk(largePayload.copyOfRange(32_768, 40_000))

        val readCnxn = transport.readPacket()
        assertEquals(AdbPacket.A_CNXN, readCnxn.command)
        assertArrayEquals(banner, readCnxn.payload)

        val readWrte = transport.readPacket()
        assertEquals(AdbPacket.A_WRTE, readWrte.command)
        assertEquals(101, readWrte.arg0)
        assertEquals(1, readWrte.arg1)
        assertArrayEquals(largePayload, readWrte.payload)
        transport.close()
    }

    @Test
    fun `rejects mid-payload ZLP truncation, corrupted header magic, and bad payload checksum`() {
        // 1. Mid-payload ZLP (truncated payload).
        val truncatedFake = RecordingUsbBulkConnection()
        val truncatedTransport = AdbUsbTransport(truncatedFake)
        val wrteHeader = AdbPacket(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_TOKEN, 0, ByteArray(20))
            .encodeHeader(skipChecksum = false)
        truncatedFake.enqueueInboundRawChunk(wrteHeader)
        truncatedFake.enqueueInboundRawChunk(ByteArray(10))
        truncatedFake.enqueueInboundRawChunk(ByteArray(0))
        try {
            truncatedTransport.readPacket()
            fail("Expected EOFException on truncated mid-payload ZLP")
        } catch (_: EOFException) {
        } finally {
            truncatedTransport.close()
        }

        // 2. Corrupted payload checksum prior to A_VERSION_SKIP_CHECKSUM.
        val badChecksumFake = RecordingUsbBulkConnection()
        val badChecksumTransport = AdbUsbTransport(badChecksumFake)
        val token = ByteArray(20) { 0x11 }
        val badHeader = ByteBuffer.allocate(AdbPacket.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbPacket.A_AUTH)
            .putInt(AdbPacket.ADB_AUTH_TOKEN)
            .putInt(0)
            .putInt(token.size)
            .putInt(999_999)
            .putInt(AdbPacket.A_AUTH xor -1)
            .array()
        badChecksumFake.enqueueInboundRawChunk(badHeader)
        badChecksumFake.enqueueInboundRawChunk(token)
        try {
            badChecksumTransport.readPacket()
            fail("Expected IOException on corrupted USB payload checksum")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("checksum") == true)
        } finally {
            badChecksumTransport.close()
        }
    }

    @Test
    fun `multiplexing poll survives idle timeouts and exits promptly on close`() {
        val fake = RecordingUsbBulkConnection()
        val transport = AdbUsbTransport(
            connection = fake,
            handshakeReadTimeoutMs = 50,
            multiplexPollTimeoutMs = 20
        )

        // Handshake mode (timeout > 0) propagates SocketTimeoutException.
        try {
            transport.readPacket()
            fail("Expected SocketTimeoutException during handshake timeout")
        } catch (_: SocketTimeoutException) {
        }

        // Multiplexing mode (timeout == 0) polls without failing across idle intervals, then reads.
        transport.clearReadTimeoutForMultiplexing()
        val readerFinished = CountDownLatch(1)
        var received: AdbPacket? = null
        val reader = Thread {
            received = transport.readPacket()
            readerFinished.countDown()
        }.apply { start() }

        while (fake.pollTimeoutsObserved.get() < 2) {
            Thread.sleep(10L)
        }
        fake.enqueueInboundPacket(AdbPacket(AdbPacket.A_OKAY, 7, 3), skipChecksum = true)
        assertTrue(readerFinished.await(2, TimeUnit.SECONDS))
        assertEquals(AdbPacket.A_OKAY, received?.command)
        reader.join(1_000L)

        // Closing while blocked in multiplexing poll wakes and throws EOFException cleanly.
        val closeLatch = CountDownLatch(1)
        val closeThread = Thread {
            try {
                transport.readPacket()
            } catch (_: EOFException) {
                closeLatch.countDown()
            }
        }.apply { start() }

        Thread.sleep(25L)
        transport.close()
        assertTrue(closeLatch.await(2, TimeUnit.SECONDS))
        closeThread.join(1_000L)
    }
}

private class RecordingUsbBulkConnection(inMaxPacketSize: Int = 512, outMaxPacketSize: Int = 512) :
    UsbBulkConnection {
    override val descriptor = AdbUsbDeviceDescriptor(
        deviceName = "/dev/bus/usb/001/002",
        deviceId = 2,
        vendorId = 0x18D1,
        productId = 0x4EE7,
        serialNumber = "test-usb-serial",
        inMaxPacketSize = inMaxPacketSize,
        outMaxPacketSize = outMaxPacketSize,
        hasUsbPermission = true
    )

    private val closed = AtomicBoolean(false)
    private val inboundQueue = LinkedBlockingQueue<ByteArray>()
    val outboundPayloads = CopyOnWriteArrayList<ByteArray>()
    val pollTimeoutsObserved = AtomicInteger(0)

    override val isClosed: Boolean
        get() = closed.get()

    fun outboundSizes(): List<Int> = outboundPayloads.map { it.size }

    fun clearOutbound() {
        outboundPayloads.clear()
    }

    fun enqueueInboundRawChunk(chunk: ByteArray) {
        inboundQueue.offer(chunk)
    }

    fun enqueueInboundPacket(packet: AdbPacket, skipChecksum: Boolean) {
        inboundQueue.offer(packet.encodeHeader(skipChecksum))
        if (packet.payload.isNotEmpty()) {
            inboundQueue.offer(packet.payload.copyOf())
        }
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureNotClosed()
        val waitMs = timeoutMs.coerceAtLeast(1).toLong()
        val chunk = inboundQueue.poll(waitMs, TimeUnit.MILLISECONDS)
        if (chunk == null) {
            ensureNotClosed()
            pollTimeoutsObserved.incrementAndGet()
            throw SocketTimeoutException("Simulated USB bulk IN timeout (${timeoutMs}ms)")
        }
        val toCopy = minOf(length, chunk.size)
        chunk.copyInto(buffer, offset, 0, toCopy)
        return toCopy
    }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        ensureNotClosed()
        outboundPayloads += buffer.copyOfRange(offset, offset + length)
        return length
    }

    private fun ensureNotClosed() {
        if (closed.get()) throw EOFException("Fake USB connection closed")
    }

    override fun close() {
        closed.set(true)
    }
}
