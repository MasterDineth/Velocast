package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.protocol.testing.HmacTestAuthenticator
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBufferPoolAndZeroAllocTest {
    private val threadMx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Suppress("DEPRECATION")
    private fun allocatedBytes(): Long = threadMx.getThreadAllocatedBytes(Thread.currentThread().id)

    @Test
    fun `frame buffer pool reuses ring slots, guards double release, and grows capacity`() {
        val pool = FrameBufferPool(capacity = 4, initialBufferBytes = 1024)
        val first = pool.acquireVideoFrame(512)
        val firstBuf = first.payload
        assertEquals(1, pool.inUseCount)
        assertEquals(1L, pool.pooledAcquireCount)
        assertEquals(0L, pool.fallbackAllocations)

        first.release()
        first.release() // Idempotent double-release must not underflow inUseCount.
        assertEquals(0, pool.inUseCount)

        // Exhaust ring of 4 slots, then verify 5th falls back cleanly without blocking.
        val held = List(4) { pool.acquireVideoFrame(256) }
        assertEquals(4, pool.inUseCount)
        val overflow = pool.acquireVideoFrame(128)
        assertEquals(1L, pool.fallbackAllocations)
        overflow.release()
        assertEquals(4, pool.inUseCount)

        held.forEach { it.release() }
        assertEquals(0, pool.inUseCount)

        // Larger frame grows the slot buffer and keeps it for reuse.
        val large = pool.acquireVideoFrame(4096)
        assertTrue(large.payload.size >= 4096)
        val grownBuf = large.payload
        large.release()

        // Acquire all 4 slots and confirm both firstBuf (1024) and grownBuf (4096) remain pooled.
        val reacquired = List(4) { pool.acquireVideoFrame(512) }
        assertTrue(reacquired.any { it.payload === firstBuf || it.payload === grownBuf })
        reacquired.forEach { it.release() }
    }

    @Test
    fun `control packet pool reuses slots and ignores duplicate release`() {
        val pool = ControlPacketPool(capacity = 3)
        val slots = List(3) { pool.acquire() }
        assertEquals(3, pool.inUseCount)
        assertEquals(0L, pool.fallbackAllocations)

        val fallback = pool.acquire()
        assertEquals(1L, pool.fallbackAllocations)
        fallback.release()

        slots[0].release()
        slots[0].release() // Idempotent.
        assertEquals(2, pool.inUseCount)

        slots[1].release()
        slots[2].release()
        assertEquals(0, pool.inUseCount)

        val reused = pool.acquire()
        assertTrue(slots.any { it === reused })
        reused.release()
    }

    @Test
    fun `pool acquire and release hot path allocates zero bytes`() {
        val framePool = FrameBufferPool(capacity = 8, initialBufferBytes = 4096)
        val packetPool = ControlPacketPool(capacity = 8)
        val source = ControlPacket().apply {
            type = MessageType.TOUCH
            pointer = 1
            param = TouchAction.MOVE.id
            x = 0.5f
            y = 0.5f
        }

        // Warm up.
        repeat(100) {
            val f = framePool.acquireVideoFrame(1024)
            f.release()
            val c = packetPool.acquire()
            c.copyFrom(source)
            c.release()
        }

        val before = allocatedBytes()
        repeat(10_000) {
            val f = framePool.acquireVideoFrame(1024)
            f.updateHeader(
                MessageType.VIDEO,
                FrameFlags.KEYFRAME,
                it,
                it.toLong(),
                it.toLong(),
                it.toLong()
            )
            f.release()

            val c = packetPool.acquire()
            c.copyFrom(source)
            c.release()
        }
        val delta = allocatedBytes() - before
        assertTrue(
            "Expected near-zero allocation in pool loop, got $delta bytes",
            delta < 16 * 1024
        )
    }

    @Test
    fun `mirror client streams 1000 video frames and touches via pools with zero fallbacks`() =
        runBlocking {
            val auth = HmacTestAuthenticator(ByteArray(32) { (it + 5).toByte() })
            val framePool = FrameBufferPool(capacity = 32, initialBufferBytes = 2048)
            val packetPool = ControlPacketPool(capacity = 128)
            val client = MirrorClient(
                ioDispatcher = Dispatchers.IO,
                frameBufferPool = framePool,
                controlPacketPool = packetPool
            )
            val totalFrames = 1_000
            val payloadSize = 1_024

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val serverError = arrayOfNulls<Throwable>(1)
                val serverThread = Thread {
                    try {
                        runPooledMockServer(server, auth, totalFrames, payloadSize)
                    } catch (t: Throwable) {
                        serverError[0] = t
                    }
                }.apply { start() }

                drivePooledClientSession(client, server.localPort, auth, totalFrames, payloadSize)
                serverThread.join(5_000L)
                if (serverError[0] != null) throw serverError[0]!!
            }

            assertEquals(totalFrames.toLong(), framePool.pooledAcquireCount)
            assertEquals(0L, framePool.fallbackAllocations)
            assertEquals(0, framePool.inUseCount)
            assertTrue(packetPool.pooledAcquireCount >= 200L)
            assertEquals(0L, packetPool.fallbackAllocations)
        }

    private fun runPooledMockServer(
        server: ServerSocket,
        auth: HmacTestAuthenticator,
        totalFrames: Int,
        payloadSize: Int
    ) {
        server.accept().use { socket ->
            val reader = ControlReader(socket.getInputStream())
            val writer = FrameWriter(socket.getOutputStream())
            val packet = ControlPacket()
            val handshake = ServerHandshake(
                authenticator = auth,
                capabilities = SessionConfig.DEFAULT_WANTED_FEATURES,
                serverNonce = ByteArray(ProtocolV2.NONCE_SIZE) { it.toByte() }
            )
            assertTrue(reader.read(packet))
            writer.writeMessage(handshake.onHello(reader.payload, 0, packet.payloadLength))
            assertTrue(reader.read(packet))
            writer.writeMessage(handshake.onClientProof(reader.payload, 0, packet.payloadLength))
            reader.phase = Phase.SESSION
            writer.phase = Phase.SESSION

            val h = FrameHeader().apply {
                type = MessageType.VIDEO
                flags = FrameFlags.KEYFRAME
            }
            val frameBuf = ByteArray(ProtocolV2.FRAME_HEADER_SIZE + payloadSize)
            repeat(totalFrames) { idx ->
                h.seq = idx
                h.ptsUs = idx * 16_667L
                h.captureUs = idx * 16_000L
                writer.writeInPlace(h, frameBuf, payloadSize)
            }
            socket.getOutputStream().flush()

            var receivedTouches = 0
            while (receivedTouches < 200 && reader.read(packet)) {
                if (packet.type == MessageType.TOUCH) receivedTouches++
            }
            assertEquals(200, receivedTouches)
        }
    }

    private suspend fun drivePooledClientSession(
        client: MirrorClient,
        port: Int,
        auth: HmacTestAuthenticator,
        totalFrames: Int,
        payloadSize: Int
    ) {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + scopeJob)
        val endpoint = ServerEndpoint("127.0.0.1", port, auth)
        try {
            client.connect(endpoint, readTimeoutMs = 5_000L, scope = scope)
            SessionHandshakeRunner.execute(
                transport = client,
                endpoint = endpoint,
                config = SessionConfig()
            ) { ByteArray(ProtocolV2.NONCE_SIZE) { it.toByte() } }

            repeat(totalFrames) {
                val f = withTimeout(5_000L) { client.readFrame() }!!
                assertEquals(MessageType.VIDEO, f.type)
                assertEquals(payloadSize, f.payloadLength)
                f.release()
            }

            val pkt = ControlPacket().apply {
                type = MessageType.TOUCH
                pointer = 0
                param = TouchAction.DOWN.id
                x = 0.5f
                y = 0.5f
            }
            client.writeControlPacket(pkt)
            pkt.param = TouchAction.MOVE.id
            repeat(198) { idx ->
                pkt.x = ((idx % 100) / 100f)
                client.writeControlPacket(pkt)
            }
            pkt.param = TouchAction.UP.id
            client.writeControlPacket(pkt)
            client.writeControl(ControlCommand.Bye(null))
        } finally {
            withContext(NonCancellable) {
                client.close()
                scopeJob.cancelAndJoin()
            }
        }
    }
}
