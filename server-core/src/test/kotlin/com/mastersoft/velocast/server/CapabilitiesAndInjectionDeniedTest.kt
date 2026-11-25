package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ClientHandshake
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.KeyAction
import com.mastersoft.velocast.protocol.KeyCodes
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.server.platform.ServerCapabilities
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesAndInjectionDeniedTest {
    @Test
    fun reportsCapabilitiesAndDegradedStatusAndDisplayPowerToggles() {
        val clock = FakeServerClock(1_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val env = FakeSessionEnvironment(clock, logger).apply {
            displayPowerSupported = true
            rotationWatcherSupported = false
        }
        val socket = FakePeerSocket()
        val session = ServerSession(
            id = 1L,
            peerSocket = socket,
            authenticator = HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET),
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = FakeHandshakeTimer(),
            handshakeTimeoutMs = 3_000L,
            sessionIdleTimeoutMs = 5_000L,
            random = SecureRandom(),
            onActivate = { it.markActiveFromGuard() },
            onAuthFailed = {},
            onClosed = {}
        )
        val thread = Thread({ session.run() }, "test-capabilities").apply { start() }
        try {
            val (cw, fr) = handshakeToSession(socket)
            verifyCapabilitiesAndPowerToggle(cw, fr, socket)
            thread.join(2_000L)
            assertEquals(listOf(false, true), env.createdControllers[0].powerChanges)
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }

    private fun verifyCapabilitiesAndPowerToggle(
        cw: ControlWriter,
        fr: FrameReader,
        socket: FakePeerSocket
    ) {
        val h = FrameHeader()
        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.STATUS, h.type)
        fr.readPayload(h)
        val capStatus = Status.decode(fr.payload, 0, h.length)
        assertEquals(StatusKind.DISPLAY_POWER, capStatus.kind)
        assertEquals(1, capStatus.value)
        assertEquals(
            "rotation_watcher=0,display_power=1,notification_shade=1,wake=1",
            capStatus.detail
        )

        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.STATUS, h.type)
        fr.readPayload(h)
        assertEquals(
            Status(StatusKind.DEGRADED, 4, "rotation_watcher"),
            Status.decode(fr.payload, 0, h.length)
        )

        cw.action(DeviceAction.DISPLAY_POWER_OFF)
        cw.action(DeviceAction.DISPLAY_POWER_ON)
        cw.bye()
        socket.clientOut.flush()

        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        assertEquals(Status(StatusKind.DISPLAY_POWER, 0), Status.decode(fr.payload, 0, h.length))

        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        assertEquals(Status(StatusKind.DISPLAY_POWER, 1), Status.decode(fr.payload, 0, h.length))
    }

    private fun handshakeToSession(socket: FakePeerSocket): Pair<ControlWriter, FrameReader> {
        val cw = ControlWriter(socket.clientOut)
        val fr = FrameReader(socket.clientIn)
        val client = ClientHandshake(
            HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET),
            ServerCapabilities.FEATURE_DISPLAY_POWER or ServerCapabilities.FEATURE_ROTATION_EVENTS,
            TestClientDriver.CLIENT_NONCE
        )
        cw.writePayload(client.hello())
        socket.clientOut.flush()
        val h = FrameHeader()
        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        cw.writePayload(client.onServerHello(fr.payload, 0, h.length))
        socket.clientOut.flush()
        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        client.onServerProof(fr.payload, 0, h.length)
        cw.phase = Phase.SESSION
        fr.phase = Phase.SESSION
        assertTrue(fr.readHeader(h))
        assertEquals(MessageType.DEVICE_INFO, h.type)
        fr.readPayload(h)
        return Pair(cw, fr)
    }

    @Test
    fun reportsInjectionDeniedNonFatallyAndKeepsSessionAndPointerStateHealthy() {
        val clock = FakeServerClock(1_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val denyingSink = object : InjectorSink {
            override fun injectTouch(
                downTimeMs: Long,
                eventTimeMs: Long,
                action: Int,
                pointerCount: Int,
                pointerIds: IntArray,
                xCoords: FloatArray,
                yCoords: FloatArray
            ): Unit = throw InjectionDeniedException("INJECT_EVENTS permission denied")

            override fun injectKey(
                downTimeMs: Long,
                eventTimeMs: Long,
                action: Int,
                keyCode: Int
            ): Unit = throw InjectionDeniedException("Key injection denied")

            override fun expandNotificationShade() = Unit
            override fun collapseNotificationShade() = Unit
            override fun toggleScreenRotation() = Unit
        }
        val env = object : SessionEnvironment {
            override val capabilities: Int = 0
            override fun queryDeviceInfo() = FakeSessionEnvironment(clock, logger).sampleDeviceInfo
            override fun createInjector(display: DisplayGeometry) =
                InputInjector(display.width, display.height, denyingSink, clock, logger)

            override fun createMediaPipeline(
                display: DisplayGeometry,
                sink: SessionFrameSink,
                onGeometryChanged: (DisplayGeometry) -> Unit
            ) = FakeMediaPipeline(AtomicInteger(), AtomicInteger())
        }
        val socket = FakePeerSocket()
        val session = ServerSession(
            id = 1L,
            peerSocket = socket,
            authenticator = HmacSha256Authenticator(TestClientDriver.DEFAULT_SECRET),
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = FakeHandshakeTimer(),
            handshakeTimeoutMs = 3_000L,
            sessionIdleTimeoutMs = 5_000L,
            random = SecureRandom(),
            onActivate = { it.markActiveFromGuard() },
            onAuthFailed = {},
            onClosed = {}
        )
        val thread = Thread({ session.run() }, "test-injection-denied").apply { start() }
        try {
            val (cw, fr) = TestClientDriver.authenticate(socket)
            cw.touch(0, TouchAction.DOWN, 0.5f, 0.5f)
            cw.touch(0, TouchAction.MOVE, 0.6f, 0.6f)
            cw.touch(0, TouchAction.UP, 0.6f, 0.6f)
            cw.key(KeyCodes.BACK, KeyAction.DOWN)
            cw.ping(777)
            cw.bye()
            socket.clientOut.flush()

            val h = FrameHeader()
            val errors = mutableListOf<ErrorMessage>()
            var gotPong = false
            while (fr.readHeader(h)) {
                fr.readPayload(h)
                when (h.type) {
                    MessageType.ERROR -> errors += ErrorMessage.decode(fr.payload, 0, h.length)

                    MessageType.PONG -> {
                        assertEquals(777, Pong.decode(fr.payload, 0, h.length).token)
                        gotPong = true
                    }

                    else -> Unit
                }
            }
            thread.join(2_000L)
            assertTrue("Session should remain alive after INJECTION_DENIED", gotPong)
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.all { it.code == ErrorCode.INJECTION_DENIED && !it.fatal })
            assertFalse(errors.isEmpty())
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }
}
