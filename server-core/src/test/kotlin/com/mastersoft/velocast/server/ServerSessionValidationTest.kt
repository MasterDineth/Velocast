package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.SessionCipher
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.TouchAction
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSessionValidationTest {
    private fun runSessionWithBadBytes(
        payloadSender: (OutputStream, SessionCipher, Int) -> Unit,
        expectedCode: ErrorCode,
        sendDownFirst: Boolean = false
    ) {
        val clock = FakeServerClock(1_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val env = FakeSessionEnvironment(clock, logger)
        val socket = FakePeerSocket()
        lateinit var session: ServerSession
        session = ServerSession(
            id = 1L,
            peerSocket = socket,
            authenticator = HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET),
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
        val thread = Thread({ session.run() }, "test-session").apply { start() }
        try {
            val (cw, fr) = TestClientDriver.authenticate(socket)
            if (sendDownFirst) {
                cw.touch(0, TouchAction.DOWN, 0.5f, 0.5f)
                socket.clientOut.flush()
                waitUntil { env.sink.touchCalls.size == 1 }
            }
            payloadSender(socket.clientOut, checkNotNull(cw.cipher), cw.nextSeq)
            socket.clientOut.flush()

            assertEquals(expectedCode, TestClientDriver.expectFatalError(fr))
            waitUntil { socket.isClosed }
            thread.join(2_000L)
            assertEquals(SessionState.Closing, session.state)
            assertTrue(env.createdPipelines[0].closed)
            if (sendDownFirst) {
                assertEquals(
                    android.view.MotionEvent.ACTION_CANCEL,
                    env.sink.touchCalls.last().action
                )
            }
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }

    @Test
    fun rejectsUnknownMessageType() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(out, cipher, rawControl(typeId = 0x7F, seq = seq))
            },
            expectedCode = ErrorCode.UNKNOWN_MESSAGE_TYPE
        )
    }

    @Test
    fun rejectsHandshakeMessageInSessionPhase() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(typeId = MessageType.HELLO.id, seq = seq, param = 26)
                )
            },
            expectedCode = ErrorCode.UNEXPECTED_MESSAGE
        )
    }

    @Test
    fun rejectsOutOfRangePointerId() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(
                        typeId = MessageType.TOUCH.id,
                        pointer = 32,
                        seq = seq,
                        param = TouchAction.DOWN.id,
                        x = 0.5f,
                        y = 0.5f
                    )
                )
            },
            expectedCode = ErrorCode.INVALID_POINTER
        )
    }

    @Test
    fun rejectsInvalidCoordinates() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(
                        typeId = MessageType.TOUCH.id,
                        pointer = 0,
                        seq = seq,
                        param = TouchAction.DOWN.id,
                        x = Float.NaN,
                        y = 0.5f
                    )
                )
            },
            expectedCode = ErrorCode.INVALID_COORDINATE
        )
    }

    @Test
    fun rejectsDisallowedKeyCode() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(typeId = MessageType.KEY.id, pointer = 0, seq = seq, param = 5)
                )
            },
            expectedCode = ErrorCode.KEY_NOT_ALLOWED
        )
    }

    @Test
    fun rejectsOutOfRangeConfigStream() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(
                        typeId = MessageType.CONFIG_STREAM.id,
                        seq = seq,
                        param = 100_000,
                        x = 60f,
                        y = 1.0f
                    )
                )
            },
            expectedCode = ErrorCode.VALUE_OUT_OF_RANGE
        )
    }

    @Test
    fun rejectsInvalidUtf8TextWithNul() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                val header = rawControl(typeId = MessageType.TEXT.id, seq = seq, param = 2)
                sendEncryptedRaw(out, cipher, header + byteArrayOf('a'.code.toByte(), 0x00))
            },
            expectedCode = ErrorCode.INVALID_TEXT
        )
    }

    @Test
    fun rejectsBackwardsSequenceNumber() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, _ ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(typeId = MessageType.KEYFRAME_REQ.id, seq = 10)
                )
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(typeId = MessageType.KEYFRAME_REQ.id, seq = 5)
                )
            },
            expectedCode = ErrorCode.MALFORMED_MESSAGE
        )
    }

    @Test
    fun rejectsInvalidPointerStateAndCancelsActivePointers() {
        runSessionWithBadBytes(
            payloadSender = { out, cipher, seq ->
                sendEncryptedRaw(
                    out,
                    cipher,
                    rawControl(
                        typeId = MessageType.TOUCH.id,
                        pointer = 3,
                        seq = seq,
                        param = TouchAction.UP.id,
                        x = 0.5f,
                        y = 0.5f
                    )
                )
            },
            expectedCode = ErrorCode.INVALID_POINTER_STATE,
            sendDownFirst = true
        )
    }

    @Test
    fun pauseCancelsAllPointersAndPausesPipelineAndResumeRestores() {
        val clock = FakeServerClock(1_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val env = FakeSessionEnvironment(clock, logger)
        val socket = FakePeerSocket()
        val session = ServerSession(
            id = 1L,
            peerSocket = socket,
            authenticator = HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET),
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
        val thread = Thread({ session.run() }, "test-pause-session").apply { start() }
        try {
            val (cw, fr) = TestClientDriver.authenticate(socket)
            cw.touch(0, TouchAction.DOWN, 0.25f, 0.25f)
            cw.request(MessageType.PAUSE)
            cw.request(MessageType.RESUME)
            cw.bye()
            socket.clientOut.flush()

            val h = FrameHeader()
            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.STATUS, h.type)
            fr.readPayload(h)
            assertEquals(Status(StatusKind.PAUSED, 1), Status.decode(fr.payload, 0, h.length))

            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.STATUS, h.type)
            fr.readPayload(h)
            assertEquals(Status(StatusKind.PAUSED, 0), Status.decode(fr.payload, 0, h.length))

            thread.join(2_000L)
            assertEquals(1, env.createdPipelines[0].pauseCount.get())
            assertEquals(1, env.createdPipelines[0].resumeCount.get())
            assertEquals(android.view.MotionEvent.ACTION_CANCEL, env.sink.touchCalls.last().action)
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }

    private fun rawControl(
        typeId: Int,
        pointer: Int = 0,
        seq: Int = 0,
        param: Int = 0,
        x: Float = 0f,
        y: Float = 0f
    ): ByteArray = ByteBuffer.allocate(ProtocolV2.CONTROL_PACKET_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .put(typeId.toByte())
        .put(pointer.toByte())
        .putShort(seq.toShort())
        .putInt(param)
        .putFloat(x)
        .putFloat(y)
        .array()

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition not satisfied within ${timeoutMs}ms")
            }
            Thread.sleep(10L)
        }
    }
}

private fun sendEncryptedRaw(out: OutputStream, cipher: SessionCipher, raw: ByteArray) {
    val aad = ByteBuffer.allocate(ProtocolV2.AEAD_HEADER_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .put(1.toByte())
        .put(0.toByte())
        .putShort(cipher.currentEpoch.toShort())
        .putLong(cipher.nextCounter)
        .putInt(raw.size)
        .array()
    val sealed = cipher.seal(aad, raw)
    out.write(aad)
    out.write(sealed)
}
