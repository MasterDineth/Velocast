package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.AesGcmSessionCipher
import com.mastersoft.velocast.protocol.AuthMode
import com.mastersoft.velocast.protocol.CipherDirection
import com.mastersoft.velocast.protocol.ClientHandshake
import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.Features
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.Hello
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.TouchAction
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.PrintStream
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end `:server-core` security tests verifying wire confidentiality (`SEC-02`), downgrade
 * rejection (`SEC-01`), and rejection of replayed, reordered, tampered, and truncated AEAD frames.
 */
class ServerSessionSecurityTest {

    @Test
    fun sessionTrafficIsEncryptedOnTheWireInBothDirections() {
        val clock = FakeServerClock(2_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val env = FakeSessionEnvironment(clock, logger)
        val baseSocket = FakePeerSocket()
        val capturedC2S = ByteArrayOutputStream()
        val capturedS2C = ByteArrayOutputStream()
        val tappingSocket = WireTappingPeerSocket(baseSocket, capturedC2S, capturedS2C)

        val session = ServerSession(
            id = 1L,
            peerSocket = tappingSocket,
            authenticator = HkdfAuthenticator(
                TestClientDriver.DEFAULT_SECRET,
                AuthMode.PAIRING_SECRET
            ),
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
        val thread = Thread({ session.run() }, "test-wire-confidentiality").apply { start() }
        try {
            val (cw, fr) = handshakeAndResetWireTaps(baseSocket, capturedC2S, capturedS2C)
            val h = FrameHeader()
            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.DEVICE_INFO, h.type)
            fr.readPayload(h)
            TestClientDriver.drainInitialStatuses(fr, h)

            val secretClip = "top-secret-otp-928374"
            cw.writePayload(ClipboardMessage(paste = false, text = secretClip))
            cw.touch(0, TouchAction.DOWN, 0.5f, 0.25f)
            cw.touch(0, TouchAction.UP, 0.5f, 0.25f)
            cw.bye()
            baseSocket.clientOut.flush()
            thread.join(2_000L)

            val c2sWire = capturedC2S.toByteArray()
            val s2cWire = capturedS2C.toByteArray()
            assertTrue(c2sWire.isNotEmpty())
            assertTrue(s2cWire.isNotEmpty())
            assertFalse(containsAscii(s2cWire, "RescuePhone"))
            assertFalse(containsAscii(s2cWire, "rotation_watcher"))
            assertFalse(containsAscii(c2sWire, secretClip))
            assertEquals(listOf(secretClip), env.createdClipboards[0].writtenTexts)
        } finally {
            session.closeAndAwait()
            thread.join(2_000L)
            logger.close()
        }
    }

    @Test
    fun rejectsPlaintextDowngradeAndRateLimitsPeer() {
        val clock = FakeServerClock(5_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val acceptor = FakeServerAcceptor()
        val env = FakeSessionEnvironment(clock, logger)
        val backoff = PeerBackoffTracker(clock)
        val guard = SessionGuard(
            acceptor = acceptor,
            authenticator = HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET),
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = FakeHandshakeTimer(),
            backoffTracker = backoff
        )
        val acceptThread = Thread({ guard.runAcceptLoop() }, "test-sec-downgrade").apply {
            isDaemon = true
            start()
        }
        try {
            val downgradeSocket = FakePeerSocket(TestClientDriver.ip(70))
            acceptor.enqueue(downgradeSocket)
            val cwDown = ControlWriter(downgradeSocket.clientOut)
            val frDown = FrameReader(downgradeSocket.clientIn)
            cwDown.writePayload(
                Hello(
                    version = 2,
                    features = Features.CLIPBOARD,
                    nonce = TestClientDriver.CLIENT_NONCE
                )
            )
            downgradeSocket.clientOut.flush()
            assertEquals(ErrorCode.AUTH_FAILED, TestClientDriver.expectFatalError(frDown))
            waitUntil { backoff.failureCount(TestClientDriver.ip(70)) == 1 }
            assertEquals(0, env.deviceInfoQueries.get())
        } finally {
            guard.close()
            acceptThread.join(2_000L)
            logger.close()
        }
    }

    @Test
    fun rejectsReplayedReorderedTamperedAndTruncatedClientFrames() {
        val clock = FakeServerClock(5_000L)
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val acceptor = FakeServerAcceptor()
        val env = FakeSessionEnvironment(clock, logger)
        val guard = SessionGuard(
            acceptor = acceptor,
            authenticator = HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET),
            environment = env,
            clock = clock,
            logger = logger,
            handshakeTimer = FakeHandshakeTimer()
        )
        val acceptThread = Thread({ guard.runAcceptLoop() }, "test-sec-corrupt").apply {
            isDaemon = true
            start()
        }
        try {
            verifyCorruptedSessionRecord(acceptor, ipOctet = 71) { c2sKey ->
                val f0 = encryptPingRecord(c2sKey, counter = 0L, token = 1)
                f0 + f0
            }
            verifyCorruptedSessionRecord(acceptor, ipOctet = 72) { c2sKey ->
                val f0 = encryptPingRecord(c2sKey, counter = 0L, token = 1)
                val f1 = encryptPingRecord(c2sKey, counter = 1L, token = 2)
                f1 + f0
            }
            verifyCorruptedSessionRecord(acceptor, ipOctet = 73) { c2sKey ->
                encryptPingRecord(c2sKey, counter = 0L, token = 1).also { it[1] = 0x01 }
            }
            verifyCorruptedSessionRecord(
                guardFixture = acceptor,
                ipOctet = 74,
                expectedError = ErrorCode.TRUNCATED,
                closeOutputAfterSend = true
            ) { c2sKey ->
                val full = encryptPingRecord(c2sKey, counter = 0L, token = 1)
                full.copyOf(full.size - 3)
            }
        } finally {
            guard.close()
            acceptThread.join(2_000L)
            logger.close()
        }
    }

    private fun handshakeAndResetWireTaps(
        socket: FakePeerSocket,
        capturedC2S: ByteArrayOutputStream,
        capturedS2C: ByteArrayOutputStream
    ): Pair<ControlWriter, FrameReader> {
        val cw = ControlWriter(socket.clientOut)
        val fr = FrameReader(socket.clientIn)
        val client = ClientHandshake(
            HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET, AuthMode.PAIRING_SECRET),
            Features.SECURE_CHANNEL or Features.CLIPBOARD,
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

        capturedC2S.reset()
        capturedS2C.reset()

        val keys = checkNotNull(client.deriveSessionKeys())
        try {
            cw.cipher = keys.newClientOutboundCipher()
            fr.cipher = keys.newClientInboundCipher()
        } finally {
            keys.close()
        }
        cw.phase = Phase.SESSION
        fr.phase = Phase.SESSION
        return cw to fr
    }

    private fun verifyCorruptedSessionRecord(
        guardFixture: FakeServerAcceptor,
        ipOctet: Int,
        expectedError: ErrorCode = ErrorCode.AUTH_FAILED,
        closeOutputAfterSend: Boolean = false,
        buildAttackBytes: (c2sKey: ByteArray) -> ByteArray
    ) {
        val peer = FakePeerSocket(TestClientDriver.ip(ipOctet))
        guardFixture.enqueue(peer)
        val cw = ControlWriter(peer.clientOut)
        val fr = FrameReader(peer.clientIn)
        val hs = ClientHandshake(
            HkdfAuthenticator(TestClientDriver.DEFAULT_SECRET),
            Features.SECURE_CHANNEL,
            TestClientDriver.CLIENT_NONCE
        )
        cw.writePayload(hs.hello())
        peer.clientOut.flush()
        val h = FrameHeader()
        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        cw.writePayload(hs.onServerHello(fr.payload, 0, h.length))
        peer.clientOut.flush()
        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        hs.onServerProof(fr.payload, 0, h.length)

        val keys = checkNotNull(hs.deriveSessionKeys())
        val c2sKey = keys.copyClientToServerKey()
        fr.cipher = keys.newClientInboundCipher()
        keys.close()
        fr.phase = Phase.SESSION

        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        TestClientDriver.drainInitialStatuses(fr, h)

        val attackBytes = buildAttackBytes(c2sKey)
        c2sKey.fill(0)
        peer.clientOut.write(attackBytes)
        peer.clientOut.flush()
        if (closeOutputAfterSend) {
            peer.closeClientOutput()
        }

        assertEquals(expectedError, expectFatalErrorAfterOptionalPong(fr))
        waitUntil { peer.isClosed }
    }

    private fun expectFatalErrorAfterOptionalPong(fr: FrameReader): ErrorCode {
        val h = FrameHeader()
        assertTrue(fr.readHeader(h))
        fr.readPayload(h)
        if (h.type == MessageType.PONG) {
            assertTrue(fr.readHeader(h))
            fr.readPayload(h)
        }
        assertEquals(MessageType.ERROR, h.type)
        val err = com.mastersoft.velocast.protocol.ErrorMessage.decode(fr.payload, 0, h.length)
        assertTrue(err.fatal)
        return err.code
    }

    private fun encryptPingRecord(c2sKey: ByteArray, counter: Long, token: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val cipher = AesGcmSessionCipher(c2sKey, CipherDirection.CLIENT_TO_SERVER)
        repeat(counter.toInt()) {
            ControlWriter(ByteArrayOutputStream()).apply {
                this.cipher = cipher
                this.phase = Phase.SESSION
                ping(0)
            }
        }
        ControlWriter(out).apply {
            this.cipher = cipher
            this.phase = Phase.SESSION
            ping(token)
        }
        cipher.close()
        return out.toByteArray()
    }

    private fun containsAscii(bytes: ByteArray, needle: String): Boolean {
        val target = needle.toByteArray(Charsets.UTF_8)
        for (i in 0..(bytes.size - target.size)) {
            var ok = true
            for (j in target.indices) {
                if (bytes[i + j] != target[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition not satisfied within ${timeoutMs}ms")
            }
            Thread.sleep(10L)
        }
    }

    private class WireTappingPeerSocket(
        private val delegate: FakePeerSocket,
        private val c2sTap: ByteArrayOutputStream,
        private val s2cTap: ByteArrayOutputStream
    ) : PeerSocket by delegate {
        override val inputStream = object : FilterInputStream(delegate.inputStream) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) synchronized(c2sTap) { c2sTap.write(b) }
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) synchronized(c2sTap) { c2sTap.write(b, off, n) }
                return n
            }
        }

        override val outputStream = object : FilterOutputStream(delegate.outputStream) {
            override fun write(b: Int) {
                synchronized(s2cTap) { s2cTap.write(b) }
                out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                synchronized(s2cTap) { s2cTap.write(b, off, len) }
                out.write(b, off, len)
            }
        }
    }
}
