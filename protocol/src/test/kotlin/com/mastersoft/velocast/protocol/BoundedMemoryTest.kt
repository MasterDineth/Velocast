package com.mastersoft.velocast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The hot paths do not allocate, and a hostile length field cannot make a reader allocate much. */
class BoundedMemoryTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Suppress("DEPRECATION")
    private fun allocatedBytes(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().id)

    private fun measure(block: () -> Unit): Long {
        block() // warm up
        val before = allocatedBytes()
        block()
        return allocatedBytes() - before
    }

    private val slack = 16 * 1024L

    @Test
    fun controlReaderDoesNotAllocatePerPacket() {
        val rnd = Random(1)
        val out = ByteArrayOutputStream()
        val writer = ControlWriter(out).also { it.phase = Phase.SESSION }
        repeat(100_000) {
            writer.touch(rnd.nextInt(32), TouchAction.MOVE, rnd.nextFloat(), rnd.nextFloat())
        }
        val bytes = out.toByteArray()
        val p = ControlPacket()
        val allocated = measure {
            val reader = ControlReader(ByteArrayInputStream(bytes)).also {
                it.phase = Phase.SESSION
            }
            var n = 0
            while (reader.read(p)) n++
            assertEquals(100_000, n)
        }
        assertTrue("allocated $allocated bytes for 100k packets", allocated < slack + 2_000)
    }

    @Test
    fun controlWriterDoesNotAllocatePerPacket() {
        val sink = OutputStream.nullOutputStream()
        val writer = ControlWriter(sink).also { it.phase = Phase.SESSION }
        val allocated = measure {
            repeat(100_000) {
                writer.touch(it and 31, TouchAction.MOVE, 0.25f, 0.75f)
                writer.key(KeyCodes.BACK, KeyAction.DOWN)
                writer.ping(it)
            }
        }
        assertTrue("allocated $allocated bytes for 300k packets", allocated < slack)
    }

    @Test
    fun frameReaderAndWriterDoNotAllocatePerFrame() {
        val frames = 20_000
        val size = 1_000
        val frame = ByteArray(ProtocolV2.FRAME_HEADER_SIZE + size)
        val h = FrameHeader().also {
            it.type = MessageType.VIDEO
            it.flags = FrameFlags.KEYFRAME
        }
        val out = ByteArrayOutputStream(frames * (size + 30))
        val writer = FrameWriter(out).also { it.phase = Phase.SESSION }
        val writeAllocated = measure {
            out.reset()
            repeat(frames) {
                h.seq = it
                h.ptsUs = it * 16_667L
                h.captureUs = it * 16_000L
                writer.writeInPlace(h, frame, size)
            }
        }
        assertTrue("writer allocated $writeAllocated bytes", writeAllocated < slack)
        val bytes = out.toByteArray()
        val dest = ByteArray(4_096)
        val back = FrameHeader()
        val readAllocated = measure {
            val reader = FrameReader(ByteArrayInputStream(bytes)).also { it.phase = Phase.SESSION }
            var n = 0
            while (reader.readHeader(back)) {
                reader.readPayload(back, dest, 0)
                n++
            }
            assertEquals(frames, n)
        }
        assertTrue("reader allocated $readAllocated bytes", readAllocated < slack + 2_000)
    }

    @Test
    fun announcingAHugeLengthAndSendingNothingCostsLittle() {
        val inputs = 5_000
        val header = VectorSupport.frame(
            0x10,
            length = ProtocolV2.MAX_PAYLOAD,
            payload = ByteArray(10)
        )
        val allocated = measure {
            repeat(inputs) {
                val reader = FrameReader(ByteArrayInputStream(header)).also {
                    it.phase =
                        Phase.SESSION
                }
                val h = FrameHeader()
                check(reader.readHeader(h))
                try {
                    reader.readPayload(h)
                    fail("truncated payload accepted")
                } catch (e: ProtocolException) {
                    assertEquals(ErrorCode.TRUNCATED, e.code)
                }
            }
        }
        val perInput = allocated / inputs
        assertTrue(
            "$perInput bytes per hostile frame; a 4 MiB buffer would be 4194304",
            perInput < 16 * 1024
        )
    }

    @Test
    fun announcingAHugeControlPayloadCostsLittle() {
        val inputs = 5_000
        val packet = VectorSupport.control(
            0x15,
            param = ProtocolV2.MAX_CLIPBOARD_BYTES,
            payload = ByteArray(10)
        )
        val allocated = measure {
            repeat(inputs) {
                val reader = ControlReader(ByteArrayInputStream(packet)).also {
                    it.phase =
                        Phase.SESSION
                }
                try {
                    reader.read(ControlPacket())
                    fail("truncated payload accepted")
                } catch (e: ProtocolException) {
                    assertEquals(ErrorCode.TRUNCATED, e.code)
                }
            }
        }
        assertTrue("${allocated / inputs} bytes per hostile packet", allocated / inputs < 16 * 1024)
    }

    @Test
    fun beforeAuthenticationNothingBiggerThanTheHandshakeCapIsEverRead() {
        val packet = VectorSupport.control(0x01, param = 100_000, payload = ByteArray(10))
        val input = ByteArrayInputStream(packet)
        try {
            ControlReader(input).read(ControlPacket())
            fail("oversized HELLO accepted")
        } catch (e: ProtocolException) {
            assertEquals(ErrorCode.MESSAGE_TOO_LARGE, e.code)
        }
        assertEquals("payload must not be consumed", 10, input.available())
    }

    @Test
    fun internalBuffersNeverExceedTheDeclaredLimit() {
        val payload = ByteArray(3_000_000) { 1 }
        val bytes = VectorSupport.frame(0x10, length = payload.size, payload = payload)
        val reader = FrameReader(ByteArrayInputStream(bytes)).also { it.phase = Phase.SESSION }
        val h = FrameHeader()
        assertTrue(reader.readHeader(h))
        reader.readPayload(h)
        assertTrue(reader.payload.size in payload.size..ProtocolV2.MAX_PAYLOAD)
    }
}
