package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.PointerStateTracker
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.protocol.Validators
import com.mastersoft.velocast.protocol.VideoSize
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TouchPipelineTest {

    @Test
    fun `viewport mapper applies VIDEO_SIZE letterbox bounds and eliminates negative zero`() {
        val mapper = ViewportMapper()
        mapper.updateContainerSize(1000, 1000)
        mapper.updateVideoSize(VideoSize(500, 1000, DisplayGeometry(1080, 2160, 0)))

        val rect = mapper.letterboxRect
        assertEquals(250f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(500f, rect.width, 0.01f)
        assertEquals(1000f, rect.height, 0.01f)

        // Touches inside black letterbox bars are ignored for DOWN.
        assertNull(mapper.mapTouchDown(200f, 500f))
        assertNull(mapper.mapTouchDown(800f, 500f))

        // Active MOVE/UP outside letterbox bars clamps cleanly to [0, 1] with positive +0.0f.
        val clampedLeft = mapper.mapActivePointer(100f, -0.0f)!!
        assertEquals(0, clampedLeft.x.toRawBits())
        assertEquals(0, clampedLeft.y.toRawBits())
        Validators.coordinate(clampedLeft.x)
        Validators.coordinate(clampedLeft.y)

        val rightBottom = mapper.mapTouchDown(750f, 1000f)!!
        assertEquals(1.0f, rightBottom.x, 0.001f)
        assertEquals(1.0f, rightBottom.y, 0.001f)
    }

    @Test
    fun `coalesces MOVE per pointer (latest wins) while keeping DOWN and UP strictly ordered`() =
        runTest {
            val transport = FakeStreamTransport()
            val sendGate = CompletableDeferred<Unit>()
            var blockMoves = true
            transport.onWrite = { cmd ->
                if (blockMoves && cmd is ControlCommand.Touch) {
                    sendGate.await()
                }
            }

            val pipeline = TouchPipeline(transport, backgroundScope)
            // First DOWN starts sending and hits sendGate so subsequent events queue up.
            assertTrue(pipeline.onDown(0, 0.1f, 0.1f))
            runCurrent()

            // Rapid MOVE flood on pointer 0 and pointer 1 while sender is busy.
            assertTrue(pipeline.onDown(1, 0.8f, 0.8f))
            for (i in 1..50) {
                assertTrue(pipeline.onMove(0, i / 100f, 0.2f))
                assertTrue(pipeline.onMove(1, 0.9f, i / 100f))
            }
            assertTrue(pipeline.onUp(0, 0.50f, 0.20f))

            // New gesture on pointer 0 must NOT coalesce with the pre-UP MOVE.
            assertTrue(pipeline.onDown(0, 0.05f, 0.05f))
            assertTrue(pipeline.onMove(0, 0.09f, 0.09f))
            assertTrue(pipeline.onUp(0, 0.10f, 0.10f))
            assertTrue(pipeline.onUp(1, 0.90f, 0.50f))

            // Unblock sender and drain.
            blockMoves = false
            sendGate.complete(Unit)
            runCurrent()

            val touches = transport.sentCommands.filterIsInstance<ControlCommand.Touch>()
            // Expected exact sequence:
            // P0 DOWN(0.1), P1 DOWN(0.8), P0 MOVE(0.50), P1 MOVE(0.50), P0 UP(0.50),
            // P0 DOWN(0.05), P0 MOVE(0.09), P0 UP(0.10), P1 UP(0.90) -> 9 packets total (not 107!).
            assertEquals(9, touches.size)

            val tracker = PointerStateTracker()
            touches.forEach { t -> tracker.onTouch(t.pointer, t.action) }
            assertEquals(0, tracker.activeCount)

            val firstMoveP0 = touches[2]
            assertEquals(0, firstMoveP0.pointer)
            assertEquals(TouchAction.MOVE, firstMoveP0.action)
            assertEquals(0.50f, firstMoveP0.x, 0.001f)

            val secondMoveP0 = touches[6]
            assertEquals(0, secondMoveP0.pointer)
            assertEquals(TouchAction.MOVE, secondMoveP0.action)
            assertEquals(0.09f, secondMoveP0.x, 0.001f)

            pipeline.close()
        }

    @Test
    fun `no stuck touches on pause, disconnect, or transport drop`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val pipelineRef = arrayOfNulls<TouchPipeline>(1)
        val hooks = object : SessionLifecycleHooks {
            override suspend fun onBeforePause() {
                pipelineRef[0]?.flushCancelOnPauseOrDisconnect()
            }

            override suspend fun onBeforeDisconnect() {
                pipelineRef[0]?.flushCancelOnPauseOrDisconnect()
            }

            override fun onConnectionLost() {
                pipelineRef[0]?.resetOnTransportLost()
            }
        }
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = transport,
            parentScope = this,
            clock = clock,
            lifecycleHooks = hooks
        )
        val pipeline = TouchPipeline(transport, backgroundScope)
        pipelineRef[0] = pipeline

        engine.connect()
        runCurrent()

        // Press two fingers and pause: CANCEL must precede PAUSE on the wire and clear local pointers.
        assertTrue(pipeline.onDown(0, 0.2f, 0.3f))
        assertTrue(pipeline.onDown(2, 0.7f, 0.4f))
        runCurrent()
        assertEquals(2, pipeline.activePointerCount)

        engine.pause()
        runCurrent()

        assertEquals(0, pipeline.activePointerCount)
        val pauseIdx = transport.sentCommands.indexOfLast { it is ControlCommand.Pause }
        assertTrue(pauseIdx > 0)
        val cmdBeforePause = transport.sentCommands[pauseIdx - 1] as ControlCommand.Touch
        assertEquals(TouchAction.CANCEL, cmdBeforePause.action)

        // Resume, press pointer 1, then disconnect -> CANCEL must precede BYE.
        engine.resume()
        runCurrent()
        assertTrue(pipeline.onDown(1, 0.5f, 0.5f))
        runCurrent()
        assertEquals(1, pipeline.activePointerCount)

        engine.disconnect()
        runCurrent()
        assertEquals(0, pipeline.activePointerCount)
        val byeIdx = transport.sentCommands.indexOfLast { it is ControlCommand.Bye }
        assertTrue(byeIdx > 0)
        val cmdBeforeBye = transport.sentCommands[byeIdx - 1] as ControlCommand.Touch
        assertEquals(TouchAction.CANCEL, cmdBeforeBye.action)

        // Verify server-side PointerStateTracker accepts the entire command trace cleanly.
        val tracker = PointerStateTracker()
        transport.sentCommands.filterIsInstance<ControlCommand.Touch>().forEach { t ->
            tracker.onTouch(t.pointer, t.action)
        }
        assertEquals(0, tracker.activeCount)

        // Reconnect and verify transport drop resets active pointers locally.
        engine.connect()
        runCurrent()
        assertTrue(pipeline.onDown(3, 0.4f, 0.4f))
        runCurrent()
        assertEquals(1, pipeline.activePointerCount)
        transport.enqueueFailure(IOException("wifi lost"))
        runCurrent()
        assertEquals(0, pipeline.activePointerCount)

        engine.disconnect()
        pipeline.close()
    }
}
