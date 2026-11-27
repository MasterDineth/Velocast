package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.VideoSize
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEngineTransitionsTest {

    @Test
    fun `connect walks Discovering Connecting StartingServer Handshaking Streaming`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher()
        val transport = FakeStreamTransport()
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = transport,
            parentScope = backgroundScope,
            config = SessionConfig(
                initialStreamConfig = StreamConfig(8_000_000, 60, 1.0f),
                targetId = "old-pixel"
            ),
            clock = clock
        )

        val observed = mutableListOf<SessionState>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.state.collect { observed += it }
        }

        engine.connect()
        runCurrent()

        assertEquals(
            listOf(
                SessionState.Idle::class,
                SessionState.Discovering::class,
                SessionState.Connecting::class,
                SessionState.StartingServer::class,
                SessionState.Handshaking::class,
                SessionState.Streaming::class
            ),
            observed.map { it::class }
        )
        val streaming = engine.state.value as SessionState.Streaming
        assertEquals(ProtocolV2.VERSION, streaming.negotiatedVersion)
        assertEquals(SessionConfig.DEFAULT_WANTED_FEATURES, streaming.negotiatedFeatures)
        assertTrue(transport.sentCommands.any { it is ControlCommand.ConfigStream })
        assertEquals(5_000L, transport.connectRecords.single().readTimeoutMs)

        engine.disconnect()
        assertEquals(SessionState.Idle, engine.state.value)
        assertTrue(transport.sentCommands.last() is ControlCommand.Bye)
        assertEquals(1, launcher.stopCount)
        collectJob.cancel()
    }

    @Test
    fun `launcher errors transition to Failed and user retry transitions Failed to Idle`() =
        runTest {
            val clock = TestSchedulerClock(testScheduler)
            val launcher = FakeServerLauncher()
            val transport = FakeStreamTransport()
            launcher.onStart = { attempt, _ ->
                if (attempt == 1) {
                    throw SessionErrorException(SessionError.DebuggingOff("enable adb"))
                }
                defaultTestEndpoint()
            }
            val engine = SessionEngine(
                serverLauncher = launcher,
                transport = transport,
                parentScope = this,
                clock = clock
            )

            engine.connect()
            runCurrent()

            val failed = engine.state.value as SessionState.Failed
            assertEquals(SessionError.DebuggingOff("enable adb"), failed.error)

            engine.connect()
            runCurrent()
            assertTrue(engine.state.value is SessionState.Streaming)

            engine.disconnect()
            assertEquals(SessionState.Idle, engine.state.value)
        }

    @Test
    fun `launcher IOException transitions to Failed ServerStartFailed`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher()
        val transport = FakeStreamTransport()
        launcher.onStart = { _, _ -> throw IOException("adb start failed") }
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()

        val failed = engine.state.value as SessionState.Failed
        assertTrue(failed.error is SessionError.ServerStartFailed)
        assertEquals("adb start failed", failed.error.detail)
    }

    @Test
    fun `handshake ERROR frame maps directly to Failed`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport().apply { autoHandshake = false }
        transport.onWrite = { cmd ->
            if (cmd is ControlCommand.HelloCommand) {
                transport.enqueuePayload(
                    ErrorMessage(ErrorCode.VERSION_MISMATCH, fatal = true, detail = "upgrade")
                )
            }
        }
        val engine = SessionEngine(launcher, transport, this, clock = clock)

        engine.connect()
        runCurrent()

        val failed = engine.state.value as SessionState.Failed
        assertEquals(SessionError.VersionMismatch("upgrade"), failed.error)
    }

    @Test
    fun `session frames update Streaming state and forward to consumer`() = runTest {
        val clock = TestSchedulerClock(testScheduler)
        val launcher = FakeServerLauncher(emitPhases = false)
        val transport = FakeStreamTransport()
        val consumer = RecordingFrameConsumer()
        val engine = SessionEngine(
            serverLauncher = launcher,
            transport = transport,
            parentScope = this,
            clock = clock,
            frameConsumer = consumer
        )

        engine.connect()
        runCurrent()

        val deviceInfo =
            DeviceInfo(34, LaunchMode.SHELL, DisplayGeometry(1080, 2400, 0), "Google", "Pixel")
        val videoSize = VideoSize(720, 1600, DisplayGeometry(1080, 2400, 1))
        val codecConfig = CodecConfig(Codec.H264, byteArrayOf(0, 0, 0, 1, 0x67))
        val videoFrame =
            ReceivedFrame(MessageType.VIDEO, FrameFlags.KEYFRAME, 1, 100L, 90L, byteArrayOf(1, 2))
        val clipboard = ClipboardMessage(paste = false, text = "hello")

        transport.enqueuePayload(deviceInfo)
        transport.enqueuePayload(codecConfig, flags = FrameFlags.CONFIG)
        transport.enqueuePayload(videoSize, flags = FrameFlags.RESET)
        transport.enqueueFrame(videoFrame)
        transport.enqueuePayload(Status(StatusKind.DISPLAY_POWER, 0))
        transport.enqueuePayload(Status(StatusKind.SCREEN_LOCKED, 1))
        transport.enqueuePayload(Status(StatusKind.BITRATE, 6_000_000))
        transport.enqueuePayload(Status(StatusKind.FPS, 60))
        transport.enqueuePayload(clipboard)
        transport.enqueuePayload(
            ErrorMessage(ErrorCode.INJECTION_DENIED, fatal = false, detail = "miui")
        )
        runCurrent()

        val streaming = engine.state.value as SessionState.Streaming
        assertEquals(deviceInfo, streaming.deviceInfo)
        assertEquals(videoSize, streaming.videoSize)
        assertFalse(streaming.isDisplayPowerOn)
        assertTrue(streaming.isScreenLocked)
        assertEquals(6_000_000, streaming.bitrateBps)
        assertEquals(60, streaming.fps)
        assertEquals(SessionError.InjectionDenied("miui"), streaming.nonFatalError)

        assertEquals(listOf(deviceInfo), consumer.deviceInfos)
        assertEquals(listOf(videoSize), consumer.videoSizes)
        assertEquals(listOf(codecConfig to FrameFlags.CONFIG), consumer.codecConfigs)
        assertEquals(listOf(videoFrame), consumer.videoFrames)
        assertEquals(listOf(clipboard), consumer.clipboards)
        assertEquals(listOf(SessionError.InjectionDenied("miui")), consumer.nonFatalErrors)

        engine.disconnect()
    }

    @Test
    fun `fatal ERROR frames map all catalogue errors to Failed`() = runTest {
        val codes = listOf(
            ErrorCode.DEBUGGING_OFF to SessionError.DebuggingOff("d"),
            ErrorCode.DIFFERENT_NETWORK to SessionError.DifferentNetwork("d"),
            ErrorCode.AUTH_EXPIRED to SessionError.AuthExpired("d"),
            ErrorCode.SERVER_START_FAILED to SessionError.ServerStartFailed("d"),
            ErrorCode.INJECTION_DENIED to SessionError.InjectionDenied("d"),
            ErrorCode.SCREEN_OFF to SessionError.ScreenOff("d"),
            ErrorCode.LOCKED to SessionError.Locked("d"),
            ErrorCode.VERSION_MISMATCH to SessionError.VersionMismatch("d")
        )

        for ((code, expected) in codes) {
            val clock = TestSchedulerClock(testScheduler)
            val launcher = FakeServerLauncher(emitPhases = false)
            val transport = FakeStreamTransport()
            val engine = SessionEngine(launcher, transport, this, clock = clock)

            engine.connect()
            runCurrent()
            transport.enqueuePayload(ErrorMessage(code, fatal = true, detail = "d"))
            runCurrent()

            assertEquals(SessionState.Failed(expected), engine.state.value)
        }
    }
}
