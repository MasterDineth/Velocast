package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.SeqTracker
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPathStallAndPriorityTest {
    private class StallableOutputStream(private val delegate: OutputStream) :
        FilterOutputStream(delegate) {
        private val lock = ReentrantLock()
        private val unblocked = lock.newCondition()
        private var stalled = false
        val firstWriteEntered = CountDownLatch(1)

        fun setStalled(stall: Boolean) {
            lock.withLock {
                stalled = stall
                if (!stall) unblocked.signalAll()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            lock.withLock {
                if (stalled) {
                    firstWriteEntered.countDown()
                    while (stalled) {
                        unblocked.await()
                    }
                }
            }
            delegate.write(b, off, len)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun flush() {
            delegate.flush()
        }
    }

    @Test
    fun queueNeverExceedsThreeFramesUnderOneSecondStallAndPrioritizesControlFrames() {
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val pipe = InMemoryPipeStream()
        val stallOut = StallableOutputStream(pipe.output)
        val sink = SessionFrameSink(FrameWriter(stallOut), stallOut)

        val keyframeRequested = AtomicBoolean(false)
        val idrRequestCount = AtomicInteger(0)
        sink.activateSession(
            sessionId = 1L,
            logger = logger,
            onFrameDropped = {
                idrRequestCount.incrementAndGet()
                keyframeRequested.set(true)
            }
        )

        try {
            stallOut.setStalled(true)
            enqueueSingleFrame(sink, keyframe = true, ptsUs = 1_000L)
            assertTrue(stallOut.firstWriteEntered.await(2, TimeUnit.SECONDS))

            val enqueueElapsedMs = feedFramesAndControlDuringStall(sink, keyframeRequested)
            assertTrue("Producer took ${enqueueElapsedMs}ms during stall", enqueueElapsedMs < 200L)
            assertEquals(3, sink.maxObservedVideoDepth)
            assertTrue("Expected drops during stall", sink.totalDroppedVideoFrames >= 50L)
            assertTrue("Expected IDR requests on drops", idrRequestCount.get() >= 50)

            Thread.sleep(1_000L)
            stallOut.setStalled(false)
            verifyWirePriorityAndSequence(pipe)
        } finally {
            stallOut.setStalled(false)
            sink.flushAndClose()
            pipe.close()
            logger.close()
        }
    }

    private fun enqueueSingleFrame(sink: SessionFrameSink, keyframe: Boolean, ptsUs: Long) {
        val slot = sink.acquireVideoSlot(16).apply {
            buffer[ProtocolV2.FRAME_HEADER_SIZE] = if (keyframe) 0x65 else 0x41
            payloadLength = 1
            flags = if (keyframe) FrameFlags.KEYFRAME else 0
            this.ptsUs = ptsUs
            captureUs = ptsUs
        }
        sink.enqueueVideo(slot)
    }

    private fun feedFramesAndControlDuringStall(
        sink: SessionFrameSink,
        keyframeRequested: AtomicBoolean
    ): Long {
        val startNs = System.nanoTime()
        for (i in 1..60) {
            enqueueSingleFrame(sink, keyframeRequested.getAndSet(false), (i + 1) * 16_667L)
            assertTrue("Observed depth ${sink.queuedVideoDepth} > 3", sink.queuedVideoDepth <= 3)
        }
        sink.enqueuePriority(Pong(token = 42, serverUs = 999_000L))
        sink.enqueuePriority(Status(StatusKind.BITRATE, 8_000_000, "mode=CBR"))
        sink.enqueuePriority(ErrorMessage(ErrorCode.INJECTION_DENIED, false, "denied"))
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    }

    private fun verifyWirePriorityAndSequence(pipe: InMemoryPipeStream) {
        val reader = FrameReader(pipe.input).apply { phase = Phase.SESSION }
        val h = FrameHeader()
        val seqTracker = SeqTracker()
        val types = mutableListOf<MessageType>()
        val videoFrames = mutableListOf<Pair<Int, Int>>()

        while (types.size < 7 && reader.readHeader(h)) {
            seqTracker.onSeq(h.seq)
            reader.readPayload(h)
            types.add(h.type)
            if (h.type == MessageType.VIDEO) {
                videoFrames.add(h.flags to h.seq)
            }
        }

        assertEquals(MessageType.VIDEO, types[0])
        assertEquals(MessageType.PONG, types[1])
        assertEquals(MessageType.STATUS, types[2])
        assertEquals(MessageType.ERROR, types[3])
        assertTrue(types.subList(4, types.size).all { it == MessageType.VIDEO })
        assertTrue(
            "Expected at least one recovery KEYFRAME after drops",
            videoFrames.drop(1).any { (flags, _) -> (flags and FrameFlags.KEYFRAME) != 0 }
        )
        assertTrue(
            "Expected sequence gap after dropped frames, got ${videoFrames.map { it.second }}",
            videoFrames[1].second - videoFrames[0].second > 10
        )
    }

    @Test
    fun tcpPeerSocketEnablesTcpNoDelayAndConfiguresSendBufferSize() {
        val logger = Logger(stderr = PrintStream(ByteArrayOutputStream()))
        ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val client = Socket("127.0.0.1", server.localPort)
            val accepted = server.accept()
            val peerDefault = TcpPeerSocket(accepted, logger)
            assertTrue(peerDefault.tcpNoDelayEnabled)
            assertTrue(peerDefault.sendBufferSize > 0)
            peerDefault.close()
            client.close()

            val client2 = Socket("127.0.0.1", server.localPort)
            val accepted2 = server.accept()
            val peerCustom = TcpPeerSocket(accepted2, logger, sendBufferBytes = 32 * 1024)
            assertTrue(peerCustom.tcpNoDelayEnabled)
            assertTrue(peerCustom.sendBufferSize > 0)
            peerCustom.close()
            client2.close()
        }
        logger.close()
    }
}
