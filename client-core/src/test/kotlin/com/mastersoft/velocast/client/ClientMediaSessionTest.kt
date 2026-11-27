package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.KeyAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientMediaSessionTest {

    @Test
    fun `concurrent sendKeyPress and sendDeviceAction do not corrupt each other`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionScope = TestScope(dispatcher)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val session = ClientMediaSession(
            serverLauncher = launcher,
            scope = sessionScope,
            clock = TestSchedulerClock(testScheduler),
            transport = transport
        )

        session.connect()
        runCurrent()
        assertTrue(session.state.value is SessionState.Streaming)

        // Yield inside writeControlPacket so concurrent sendKeyPress/sendDeviceAction interleave.
        transport.onWrite = { yield() }

        session.sendKeyPress(10)
        session.sendKeyPress(20)
        session.sendDeviceAction(DeviceAction.HOME)
        session.sendDeviceAction(DeviceAction.BACK)
        advanceTimeBy(10L)
        runCurrent()

        val keyCommands = transport.sentCommands.filterIsInstance<ControlCommand.Key>()
        val actionCommands = transport.sentCommands.filterIsInstance<ControlCommand.Action>()

        assertEquals(2, keyCommands.count { it.keyCode == 10 })
        assertEquals(
            listOf(KeyAction.DOWN, KeyAction.UP),
            keyCommands.filter { it.keyCode == 10 }.map { it.action }
        )
        assertEquals(2, keyCommands.count { it.keyCode == 20 })
        assertEquals(
            listOf(KeyAction.DOWN, KeyAction.UP),
            keyCommands.filter { it.keyCode == 20 }.map { it.action }
        )
        assertEquals(
            setOf(DeviceAction.HOME, DeviceAction.BACK),
            actionCommands.map { it.action }.toSet()
        )

        session.close()
        sessionScope.cancel()
        runCurrent()
    }
}
