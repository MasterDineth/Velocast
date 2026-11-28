package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.AuthMode
import com.mastersoft.velocast.protocol.AuthProof
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.JvmAesGcmEnvelopeCipher
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import com.mastersoft.velocast.protocol.SessionCipher
import com.mastersoft.velocast.protocol.SessionSecrets
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end `:client-core` security wiring tests covering [AndroidKeyStoreSecretStore],
 * [HkdfAuthenticator], [MirrorClient] AEAD transport, negative attack detection, and
 * [DiagnosticsRingBuffer] secret redaction.
 */
class ClientSecurityWiringTest {

    @Test
    fun `keystore pairing secret authenticates SessionEngine over encrypted TCP`() = runBlocking {
        val dir = Files.createTempDirectory("velocast-client-keystore-test")
        try {
            val masterKey = SessionSecrets.generate()
            val store = AndroidKeyStoreSecretStore(dir, JvmAesGcmEnvelopeCipher(masterKey))
            val pairingSecret = SessionSecrets.generate()
            store.putSecret(AndroidKeyStoreSecretStore.DEFAULT_PAIRING_ALIAS, pairingSecret)

            val loaded =
                checkNotNull(store.getSecret(AndroidKeyStoreSecretStore.DEFAULT_PAIRING_ALIAS))
            val clientAuth = HkdfAuthenticator(loaded, AuthMode.PAIRING_SECRET)
            val serverAuth = HkdfAuthenticator(pairingSecret, AuthMode.PAIRING_SECRET)

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val serverThread = startDeviceInfoServer(server, serverAuth)
                val scopeJob = SupervisorJob()
                val scope = CoroutineScope(scopeJob + Dispatchers.IO)
                val engine = SessionEngine(
                    serverLauncher = fixedEndpointLauncher(server.localPort, clientAuth),
                    transport = MirrorClient(ioDispatcher = Dispatchers.IO),
                    parentScope = scope
                )
                try {
                    engine.connect()
                    val streaming = withTimeout(3_000L) {
                        engine.state.first {
                            it is SessionState.Streaming && it.deviceInfo != null
                        } as SessionState.Streaming
                    }
                    assertEquals("M2101K6G", streaming.deviceInfo?.model)
                    engine.disconnect()
                } finally {
                    withContext(NonCancellable) {
                        engine.close()
                        scopeJob.cancelAndJoin()
                    }
                    clientAuth.close()
                    serverAuth.close()
                    serverThread.join(2_000L)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `SessionEngine fails terminally on wrong server secret or tampered header`() = runBlocking {
        val clientSecret = ByteArray(32) { (it + 1).toByte() }

        assertClientFailsWithCode(clientSecret, ErrorCode.AUTH_FAILED) { socket ->
            val cr = ControlReader(socket.getInputStream())
            val fw = FrameWriter(socket.getOutputStream())
            val p = ControlPacket()
            val wrongServerAuth = HkdfAuthenticator(ByteArray(32) { 0x66 })
            val hs = ServerHandshake(
                wrongServerAuth,
                SessionConfig.DEFAULT_WANTED_FEATURES,
                ByteArray(16) { 5 }
            )
            assertTrue(cr.read(p))
            fw.writeMessage(hs.onHello(cr.payload, 0, p.payloadLength))
            assertTrue(cr.read(p))
            fw.writeMessage(AuthProof(ByteArray(32) { 9 }))
            socket.getOutputStream().flush()
            wrongServerAuth.close()
        }

        assertClientFailsWithCode(clientSecret, ErrorCode.AUTH_FAILED) { socket ->
            val (_, s2cCipher) = completeServerHandshake(socket, clientSecret)
            val tampered = encodeEncryptedPong(s2cCipher, token = 1).also { it[1] = 0x04 }
            socket.getOutputStream().write(tampered)
            socket.getOutputStream().flush()
            s2cCipher.close()
        }
    }

    @Test
    fun `SessionEngine fails terminally on replayed or reordered server AEAD frames`() =
        runBlocking {
            val clientSecret = ByteArray(32) { (it + 7).toByte() }

            assertClientFailsWithCode(clientSecret, ErrorCode.AUTH_FAILED) { socket ->
                val (_, s2cCipher) = completeServerHandshake(socket, clientSecret)
                val frame0 = encodeEncryptedPong(s2cCipher, token = 1)
                socket.getOutputStream().write(frame0)
                socket.getOutputStream().write(frame0)
                socket.getOutputStream().flush()
                s2cCipher.close()
            }

            assertClientFailsWithCode(clientSecret, ErrorCode.AUTH_FAILED) { socket ->
                val (_, s2cCipher) = completeServerHandshake(socket, clientSecret)
                val frame0 = encodeEncryptedPong(s2cCipher, token = 1)
                val frame1 = encodeEncryptedPong(s2cCipher, token = 2)
                socket.getOutputStream().write(frame1)
                socket.getOutputStream().write(frame0)
                socket.getOutputStream().flush()
                s2cCipher.close()
            }
        }

    @Test
    fun `SessionMetrics diagnostics log redacts secrets and long hex keys`() {
        val metrics = SessionMetrics()
        val rawSecret = ByteArray(32) { (0x50 + it).toByte() }
        metrics.diagnosticsLog.redactor.registerSecret(rawSecret)
        val hexSecret = rawSecret.joinToString("") { "%02x".format(it) }

        metrics.recordDiagnosticsEvent(
            "AUTH",
            "Failed connect with secret=topSecretValue raw=$hexSecret pin=771122"
        )
        val logged = metrics.diagnosticsLog.snapshot().single().message
        assertFalse(logged.contains("topSecretValue"))
        assertFalse(logged.contains(hexSecret))
        assertFalse(logged.contains("771122"))
        assertTrue(logged.contains("<redacted>"))
    }

    private fun startDeviceInfoServer(server: ServerSocket, serverAuth: HkdfAuthenticator): Thread =
        Thread {
            server.accept().use { socket ->
                val cr = ControlReader(socket.getInputStream())
                val fw = FrameWriter(socket.getOutputStream())
                val p = ControlPacket()
                val hs = ServerHandshake(
                    serverAuth,
                    SessionConfig.DEFAULT_WANTED_FEATURES,
                    ByteArray(ProtocolV2.NONCE_SIZE) { (0x30 + it).toByte() }
                )
                assertTrue(cr.read(p))
                fw.writeMessage(hs.onHello(cr.payload, 0, p.payloadLength))
                assertTrue(cr.read(p))
                fw.writeMessage(hs.onClientProof(cr.payload, 0, p.payloadLength))
                val keys = checkNotNull(hs.deriveSessionKeys())
                cr.cipher = keys.newServerInboundCipher()
                fw.cipher = keys.newServerOutboundCipher()
                keys.close()
                cr.phase = Phase.SESSION
                fw.phase = Phase.SESSION
                fw.writeMessage(
                    DeviceInfo(
                        36,
                        LaunchMode.SHELL,
                        DisplayGeometry(1080, 2400, 0),
                        "Xiaomi",
                        "M2101K6G"
                    )
                )
                socket.getOutputStream().flush()
                assertTrue(cr.read(p))
                assertEquals(MessageType.BYE, p.type)
            }
        }.apply { start() }

    private fun fixedEndpointLauncher(port: Int, auth: HkdfAuthenticator): ServerLauncher =
        object : ServerLauncher {
            override suspend fun start(onPhase: (LaunchPhase) -> Unit): ServerEndpoint {
                onPhase(LaunchPhase.CONNECTING)
                return ServerEndpoint("127.0.0.1", port, auth)
            }

            override suspend fun stop() = Unit
        }

    private suspend fun assertClientFailsWithCode(
        clientSecret: ByteArray,
        expectedCode: ErrorCode,
        serverAction: (Socket) -> Unit
    ) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = Thread {
                server.accept().use { socket -> serverAction(socket) }
            }.apply { start() }

            val auth = HkdfAuthenticator(clientSecret)
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob + Dispatchers.IO)
            val engine = SessionEngine(
                serverLauncher = fixedEndpointLauncher(server.localPort, auth),
                transport = MirrorClient(ioDispatcher = Dispatchers.IO),
                parentScope = scope,
                config = SessionConfig(
                    reconnect = ReconnectConfig(
                        initialDelayMs = 50L,
                        maxDelayMs = 50L,
                        maxDurationMs = 0L
                    )
                )
            )
            try {
                engine.connect()
                val failed = withTimeout(3_000L) {
                    engine.state.first { it is SessionState.Failed } as SessionState.Failed
                }
                assertEquals(expectedCode, failed.error.code)
            } finally {
                withContext(NonCancellable) {
                    engine.close()
                    scopeJob.cancelAndJoin()
                }
                auth.close()
                serverThread.join(2_000L)
            }
        }
    }

    private fun completeServerHandshake(
        socket: Socket,
        secret: ByteArray
    ): Pair<SessionCipher, SessionCipher> {
        val cr = ControlReader(socket.getInputStream())
        val fw = FrameWriter(socket.getOutputStream())
        val p = ControlPacket()
        val serverAuth = HkdfAuthenticator(secret)
        val hs = ServerHandshake(
            serverAuth,
            SessionConfig.DEFAULT_WANTED_FEATURES,
            ByteArray(ProtocolV2.NONCE_SIZE) { (0x20 + it).toByte() }
        )
        assertTrue(cr.read(p))
        fw.writeMessage(hs.onHello(cr.payload, 0, p.payloadLength))
        assertTrue(cr.read(p))
        fw.writeMessage(hs.onClientProof(cr.payload, 0, p.payloadLength))
        socket.getOutputStream().flush()
        val keys = checkNotNull(hs.deriveSessionKeys())
        val c2s = keys.newServerInboundCipher()
        val s2c = keys.newServerOutboundCipher()
        keys.close()
        serverAuth.close()
        return c2s to s2c
    }

    private fun encodeEncryptedPong(cipher: SessionCipher, token: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        FrameWriter(buf).apply {
            this.cipher = cipher
            this.phase = Phase.SESSION
            writeMessage(Pong(token = token, serverUs = token * 100L))
        }
        return buf.toByteArray()
    }
}
