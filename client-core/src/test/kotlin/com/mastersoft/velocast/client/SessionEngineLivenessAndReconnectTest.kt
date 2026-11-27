package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.MessageType
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEngineLivenessAndReconnectTest {

    @Test
    fun `heartbeats fire every 2s and silence triggers LINK_LOST at exactly 6s`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport().apply { autoReplyPong = false }
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)

        // Advance to 4000 ms and confirm heartbeats at 2000 ms & 4000 ms, still Streaming
        advanceTimeBy(4_000L)
        runCurrent()
        val pingCount = transport.sentCommands.count { it is ControlCommand.Ping }
        assertEquals(2, pingCount)
        assertTrue(engine.state.value is SessionState.Streaming)

        // At 5999 ms silence, still Streaming
        advanceTimeBy(1_999L)
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)

        // At 6000 ms silence, link is declared lost and engine enters Reconnecting
        advanceTimeBy(1L)
        runCurrent()
        val reconnecting = engine.state.value as SessionState.Reconnecting
        assertTrue(reconnecting.lastError is SessionError.LinkLost)
        assertEquals(1, reconnecting.attempt)
        assertEquals(1_000L, reconnecting.nextRetryDelayMs)

        engine.disconnect()
    }

    @Test
    fun `inbound frames reset 6s silence watchdog and PONG updates rtt`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport().apply { autoReplyPong = true }
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()

        // Run for 10 seconds with 2s PONG responses -> stays Streaming
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)

        // Now turn off PONG replies and send a video frame at t=14s (before 6s since t=10s)
        transport.autoReplyPong = false
        advanceTimeBy(4_000L)
        runCurrent()
        transport.enqueueFrame(
            ReceivedFrame(MessageType.VIDEO, FrameFlags.KEYFRAME, 1, 10L, 10L, byteArrayOf(1))
        )
        runCurrent()

        // At t=19_999ms (5_999ms after video frame), still Streaming
        advanceTimeBy(5_999L)
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)

        // At t=20_000ms (6_000ms after video frame), transitions to Reconnecting
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(engine.state.value is SessionState.Reconnecting)

        engine.disconnect()
    }

    @Test
    fun `socket read timeout triggers immediate reconnect`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()
        transport.enqueueFailure(SocketTimeoutException("read timed out"))
        runCurrent()

        val reconnecting = engine.state.value as SessionState.Reconnecting
        assertTrue(reconnecting.lastError is SessionError.LinkLost)

        engine.disconnect()
    }

    @Test
    fun `reconnect retries handshake first without relaunching server`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()
        assertEquals(1, launcher.startCount)

        // Drop the link
        transport.enqueueFailure(IOException("wifi blip"))
        runCurrent()
        assertTrue(engine.state.value is SessionState.Reconnecting)

        // After 1s backoff, handshake succeeds directly against cached endpoint
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(engine.state.value is SessionState.Streaming)
        assertEquals(1, launcher.startCount)
        assertEquals(2, transport.connectRecords.size)

        engine.disconnect()
    }

    @Test
    fun `reconnect relaunches server only when cached endpoint no longer answers`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val firstEndpoint = defaultTestEndpoint(port = 27183, sessionId = "s1")
        val secondEndpoint = defaultTestEndpoint(port = 27184, sessionId = "s2")
        val launcher = FakeServerLauncher(emitPhases = false).apply {
            onStart = { attempt, _ -> if (attempt == 1) firstEndpoint else secondEndpoint }
        }
        val transport = FakeStreamTransport().apply {
            onConnect = { _, endpoint ->
                // Second attempt against old port fails; new port succeeds
                if (connectRecords.size == 2 && endpoint == firstEndpoint) {
                    throw IOException("connection refused")
                }
            }
        }
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()
        assertEquals(firstEndpoint, (engine.state.value as SessionState.Streaming).endpoint)

        transport.enqueueFailure(IOException("server died"))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        val streaming = engine.state.value as SessionState.Streaming
        assertEquals(secondEndpoint, streaming.endpoint)
        assertEquals(2, launcher.startCount)

        engine.disconnect()
    }

    @Test
    fun `exponential backoff follows 1 2 4 8 15s cap and fails after 10 minutes`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()

        // Make all subsequent connects fail while launcher continues returning endpoint
        transport.onConnect = { attempt, _ ->
            if (attempt > 1) throw IOException("still offline")
        }

        transport.enqueueFailure(IOException("link down"))
        runCurrent()

        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L)
        for ((index, expectedDelay) in expectedDelays.withIndex()) {
            val state = engine.state.value as SessionState.Reconnecting
            assertEquals(index + 1, state.attempt)
            assertEquals(expectedDelay, state.nextRetryDelayMs)
            advanceTimeBy(expectedDelay)
            runCurrent()
        }

        // Advance through the remainder of the 10-minute budget (600_000 ms)
        advanceTimeBy(600_000L)
        runCurrent()

        val failed = engine.state.value as SessionState.Failed
        assertTrue(failed.error is SessionError.LinkLost)
    }

    @Test
    fun `retryNow wakes Reconnecting early without waiting full backoff`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()

        transport.enqueueFailure(IOException("brief drop"))
        runCurrent()
        assertTrue(engine.state.value is SessionState.Reconnecting)

        // Advance only 100 ms (< 1000 ms) and invoke retryNow()
        advanceTimeBy(100L)
        engine.retryNow()
        runCurrent()

        assertTrue(engine.state.value is SessionState.Streaming)
        engine.disconnect()
    }
}
