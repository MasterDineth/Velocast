package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.protocol.VideoSize
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationStateMachineTest {
    @Test
    fun pollsEvery500MsWithFakeClockAndWakesImmediatelyOnWatcherSignal() {
        val clock = FakeServerClock(1_000L)
        val liveGeometry = AtomicReference(DisplayGeometry(1080, 2400, 0))
        val queryCount = AtomicInteger(0)

        val sm = RotationStateMachine(
            initialGeometry = liveGeometry.get(),
            clock = clock,
            queryGeometry = {
                queryCount.incrementAndGet()
                liveGeometry.get()
            }
        )

        clock.advanceBy(499L)
        assertNull(sm.pollIfDue())
        assertEquals(0, queryCount.get())

        clock.advanceBy(1L)
        assertNull(sm.pollIfDue())
        assertEquals(1, queryCount.get())

        liveGeometry.set(DisplayGeometry(2400, 1080, 1))
        clock.advanceBy(499L)
        assertNull(sm.pollIfDue())
        assertEquals(1, queryCount.get())

        clock.advanceBy(1L)
        assertEquals(DisplayGeometry(2400, 1080, 1), sm.pollIfDue())
        assertEquals(2, queryCount.get())

        liveGeometry.set(DisplayGeometry(1812, 2176, 0))
        clock.advanceBy(10L)
        sm.notifyRotationChanged()
        assertEquals(DisplayGeometry(1812, 2176, 0), sm.pollIfDue())
        assertEquals(3, queryCount.get())
    }

    @Test
    fun emitsVideoSizeThenConfigThenIdrAndUpdatesInjectorAtomicallyAcrossRotations() {
        val logger = Logger(stderr = PrintStream(ByteArrayOutputStream()))
        val clock = FakeServerClock(10_000L)
        val pipe = InMemoryPipeStream()
        val sink = SessionFrameSink(FrameWriter(pipe.output), pipe.output)
        sink.activateSession(1L, logger)

        val injectorSink = FakeInjectorSink()
        val injector = InputInjector(1080, 2400, injectorSink, clock, logger)
        val currentGeom = AtomicReference(DisplayGeometry(1080, 2400, 0))
        val sm = RotationStateMachine(currentGeom.get(), clock, { currentGeom.get() })
        val rotations = listOf(
            DisplayGeometry(2400, 1080, 1),
            DisplayGeometry(1080, 2400, 2),
            DisplayGeometry(2400, 1080, 3)
        )

        try {
            val reader = FrameReader(pipe.input).apply { phase = Phase.SESSION }
            val initialGeom = currentGeom.get()
            emitGenerationAndIdr(sink, injector, clock, initialGeom, false, 1)
            assertTouchMapsTo(injector, injectorSink, 540f, 600f)
            verifySingleGeneration(
                reader,
                initialGeom,
                expectRotationStatus = false,
                expectReset = false
            )

            rotations.forEachIndexed { idx, next ->
                currentGeom.set(next)
                clock.advanceBy(500L)
                assertEquals(next, sm.pollIfDue())
                emitGenerationAndIdr(sink, injector, clock, next, true, (idx + 2).toByte())
                assertTouchMapsTo(injector, injectorSink, next.width * 0.5f, next.height * 0.25f)
                verifySingleGeneration(
                    reader,
                    next,
                    expectRotationStatus = true,
                    expectReset = true
                )
            }

            sink.flushAndClose()
            pipe.output.close()
        } finally {
            sink.flushAndClose()
            pipe.close()
            logger.close()
        }
    }

    private fun emitGenerationAndIdr(
        sink: SessionFrameSink,
        injector: InputInjector,
        clock: FakeServerClock,
        geom: DisplayGeometry,
        isReset: Boolean,
        marker: Byte
    ) {
        injector.updateDisplaySize(geom.width, geom.height)
        if (isReset) {
            sink.enqueuePriority(Status(StatusKind.ROTATION, geom.rotation))
        }
        sink.beginStreamGeneration(
            videoSize = VideoSize(geom.width, geom.height, geom),
            codecConfig = CodecConfig(Codec.H264, byteArrayOf(0, 0, 0, 1, 0x67, marker)),
            isReset = isReset
        )
        val idr = sink.acquireVideoSlot(8).apply {
            buffer[ProtocolV2.FRAME_HEADER_SIZE] = 0x65
            buffer[ProtocolV2.FRAME_HEADER_SIZE + 1] = marker
            payloadLength = 2
            flags = FrameFlags.KEYFRAME or if (isReset) FrameFlags.RESET else 0
            ptsUs = clock.monotonicMicros()
            captureUs = clock.monotonicMicros()
        }
        sink.enqueueVideo(idr)
    }

    private fun assertTouchMapsTo(
        injector: InputInjector,
        injectorSink: FakeInjectorSink,
        expectedX: Float,
        expectedY: Float
    ) {
        injector.handleTouch(0, TouchAction.DOWN, 0.5f, 0.25f)
        assertEquals(expectedX, injectorSink.touchCalls.last().xSnapshot[0], 0.01f)
        assertEquals(expectedY, injectorSink.touchCalls.last().ySnapshot[0], 0.01f)
        injector.handleTouch(0, TouchAction.UP, 0.5f, 0.25f)
    }

    private fun verifySingleGeneration(
        reader: FrameReader,
        expectedGeom: DisplayGeometry,
        expectRotationStatus: Boolean,
        expectReset: Boolean
    ) {
        val h = FrameHeader()
        if (expectRotationStatus) {
            assertTrue(reader.readHeader(h))
            assertEquals(MessageType.STATUS, h.type)
            reader.readPayload(h)
            val status = Status.decode(reader.payload, 0, h.length)
            assertEquals(Status(StatusKind.ROTATION, expectedGeom.rotation), status)
        }

        assertTrue(reader.readHeader(h))
        assertEquals(MessageType.VIDEO_SIZE, h.type)
        reader.readPayload(h)
        assertEquals(expectedGeom, VideoSize.decode(reader.payload, 0, h.length).display)

        assertTrue(reader.readHeader(h))
        assertEquals(MessageType.CONFIG, h.type)
        reader.readPayload(h)
        CodecConfig.decode(reader.payload, 0, h.length)

        assertTrue(reader.readHeader(h))
        assertEquals(MessageType.VIDEO, h.type)
        assertTrue((h.flags and FrameFlags.KEYFRAME) != 0)
        if (expectReset) {
            assertTrue((h.flags and FrameFlags.RESET) != 0)
        }
        reader.readPayload(h)
    }
}
