package com.mastersoft.velocast.client

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEnginePauseResumeAndCancellationTest {

    @Test
    fun `pause sends PAUSE and releases surface while resume sends RESUME and KEYFRAME_REQ`() =
        runTest {
            val clock = TestSchedulerClock(testScheduler)
            val launcher = FakeServerLauncher(emitPhases = false)
            val transport = FakeStreamTransport()
            val surface = FakeSurfaceController()
            val engine = SessionEngine(
                serverLauncher = launcher,
                transport = transport,
                parentScope = this,
                clock = clock,
                surfaceController = surface
            )

            engine.connect()
            runCurrent()
            assertTrue(engine.state.value is SessionState.Streaming)

            engine.pause()
            runCurrent()
            val paused = engine.state.value as SessionState.Paused
            assertTrue(paused.surfaceReleased)
            assertEquals(1, surface.releaseCount)
            assertTrue(transport.sentCommands.last() is ControlCommand.Pause)

            // Link stays alive while paused thanks to 2s heartbeats
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(engine.state.value is SessionState.Paused)

            val beforeResumeIndex = transport.sentCommands.size
            engine.resume()
            runCurrent()

            assertTrue(engine.state.value is SessionState.Streaming)
            val resumeCommands = transport.sentCommands.drop(beforeResumeIndex)
            assertEquals(
                listOf(ControlCommand.Resume, ControlCommand.KeyframeRequest),
                resumeCommands
            )

            engine.disconnect()
        }

    @Test
    fun `link lost while paused stays Paused then rediscovers on resume`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val surface = FakeSurfaceController()
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = transport,
            parentScope = this,
            clock = clock,
            surfaceController = surface
        )

        engine.connect()
        runCurrent()
        engine.pause()
        runCurrent()

        // Drop transport while paused
        transport.enqueueFailure(IOException("background network switch"))
        runCurrent()
        assertTrue(engine.state.value is SessionState.Paused)
        assertEquals(1, launcher.startCount)

        // Resume triggers full rediscover + connect + start + handshake -> Streaming
        engine.resume()
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)
        assertEquals(2, launcher.startCount)

        engine.disconnect()
    }

    @Test
    fun `cancelling parent scope from Streaming or Reconnecting leaves zero running coroutines`() =
        runTest {
            val clock = TestSchedulerClock(testScheduler)
            val childJob = SupervisorJob()
            val engineScope = kotlinx.coroutines.CoroutineScope(coroutineContext + childJob)
            val launcher = FakeServerLauncher(emitPhases = false)
            val transport = FakeStreamTransport()
            val engine = SessionEngine(launcher, transport, engineScope, clock = clock)

            engine.connect()
            runCurrent()
            assertEquals(1, transport.activeChildCount)

            // Move into Reconnecting, then cancel parent scope
            transport.enqueueFailure(IOException("drop"))
            runCurrent()
            assertTrue(engine.state.value is SessionState.Reconnecting)

            engineScope.cancel()
            runCurrent()

            assertEquals(0, transport.activeChildCount)
            assertTrue(childJob.children.none { it.isActive })
        }

    @Test
    fun `disconnect and close from Paused leave zero active coroutines`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()
        engine.pause()
        runCurrent()
        assertEquals(1, transport.activeChildCount)

        engine.disconnect()
        runCurrent()

        assertEquals(SessionState.Idle, engine.state.value)
        assertEquals(0, transport.activeChildCount)
        engine.close()
    }
}
