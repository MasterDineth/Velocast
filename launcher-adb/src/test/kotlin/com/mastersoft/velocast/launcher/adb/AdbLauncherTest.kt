package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.client.ClientSocket
import com.mastersoft.velocast.client.ClientSocketConnector
import com.mastersoft.velocast.client.DataChannelMode
import com.mastersoft.velocast.client.LaunchPhase
import com.mastersoft.velocast.client.MirrorClient
import com.mastersoft.velocast.client.SessionConfig
import com.mastersoft.velocast.client.SessionEngine
import com.mastersoft.velocast.client.SessionError
import com.mastersoft.velocast.client.SessionErrorException
import com.mastersoft.velocast.client.SessionLifecycleHooks
import com.mastersoft.velocast.client.SessionMetrics
import com.mastersoft.velocast.client.SessionState
import com.mastersoft.velocast.protocol.AuthMode
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.Features
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbLauncherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `pushes jar, pipes secret to stdin only, parses READY, and stops stream`() = runBlocking {
        val jarFile = createSampleJar()
        val expectedHash = Sha256.hashFileHex(jarFile)
        val fixedSecret = ByteArray(ProtocolV2.SECRET_SIZE) { idx -> (idx + 0x10).toByte() }
        val expectedHex = Sha256.toLowerHex(fixedSecret)

        val fakeAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = null
        )
        val diagnostics = CopyOnWriteArrayList<Pair<String, String>>()
        val launcher = AdbLauncher(
            adbClient = fakeAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199),
            secretGenerator = { fixedSecret.copyOf() },
            onDiagnostics = { cat, msg -> diagnostics += cat to msg }
        )

        val phases = ArrayList<LaunchPhase>()
        val endpoint = launcher.start { phases += it }

        assertEquals(listOf(LaunchPhase.CONNECTING, LaunchPhase.STARTING_SERVER), phases)
        assertEquals(1, fakeAdb.pushCount.get())
        assertArrayEquals(jarFile.readBytes(), fakeAdb.remoteJarBytes)
        assertEquals(expectedHash, fakeAdb.remoteSha256)

        val shellService = fakeAdb.openedServices.single()
        assertEquals(
            "shell:CLASSPATH=/data/local/tmp/velocast-server.jar app_process / " +
                "com.mastersoft.velocast.server.Main --port 0 --bind 192.168.1.42 " +
                "--secret-from-stdin",
            shellService
        )
        assertFalse("Secret must never appear in argv", shellService.contains(expectedHex))

        val shellStream = fakeAdb.shellStreams.single()
        assertEquals("$expectedHex\n", shellStream.writtenStdinText())
        assertFalse("Shell stream must stay open during active session", shellStream.isClosed)

        assertEquals("192.168.1.42", endpoint.host)
        assertEquals(41234, endpoint.port)
        assertEquals("deadbeef01020304", endpoint.sessionId)
        assertEquals(DataChannelMode.DIRECT_TCP, endpoint.dataChannelMode)
        assertEquals(AdbLauncher.REASON_WIFI_DIRECT_TCP, endpoint.dataChannelReason)
        assertNull(endpoint.tunnelConnector)

        val decision = launcher.lastDataChannelDecision.value
        assertNotNull(decision)
        assertEquals(DataChannelMode.DIRECT_TCP, decision?.mode)
        assertEquals("192.168.1.42", decision?.bindAddress)
        assertEquals(41234, decision?.remotePort)
        assertTrue(
            diagnostics.any {
                it.first == "DATA_CHANNEL" &&
                    it.second.contains("DIRECT_TCP")
            }
        )

        launcher.stop()
        assertTrue("Stopping session must close the shell stream", shellStream.isClosed)
        launcher.close()
    }

    @Test
    fun `skips push when remote SHA-256 matches and repushes on checksum mismatch`() = runBlocking {
        val jarFile = createSampleJar()
        val expectedHash = Sha256.hashFileHex(jarFile)

        val matchingAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = expectedHash
        )
        val launcher1 = AdbLauncher(
            adbClient = matchingAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199)
        )
        launcher1.start()
        assertEquals(0, matchingAdb.pushCount.get())
        launcher1.close()

        val staleAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = "0".repeat(64)
        )
        val launcher2 = AdbLauncher(
            adbClient = staleAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199)
        )
        launcher2.start()
        assertEquals(1, staleAdb.pushCount.get())
        assertEquals(expectedHash, staleAdb.remoteSha256)
        launcher2.close()

        val corruptPushAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = "0".repeat(64),
            corruptPushHash = "f".repeat(64)
        )
        val launcher3 = AdbLauncher(
            adbClient = corruptPushAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199)
        )
        try {
            launcher3.start()
            fail("Expected ServerStartFailed on post-push SHA-256 mismatch")
        } catch (e: SessionErrorException) {
            assertTrue(e.error is SessionError.ServerStartFailed)
            assertEquals(1, corruptPushAdb.pushCount.get())
            assertTrue(corruptPushAdb.openedServices.isEmpty())
        } finally {
            launcher3.close()
        }
    }

    @Test
    fun `skips preamble before READY and rejects malformed READY`() = runBlocking {
        val jarFile = createSampleJar()
        val expectedHash = Sha256.hashFileHex(jarFile)

        val noisyAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = expectedHash,
            shellReplyFactory = {
                "WARNING: linker notice\n\nREADY port=45001 nonce=cafebabe\n"
            }
        )
        val launcher1 = AdbLauncher(
            adbClient = noisyAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199)
        )
        val endpoint = launcher1.start()
        assertEquals(45001, endpoint.port)
        assertEquals("cafebabe", endpoint.sessionId)
        launcher1.close()

        val malformedAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = expectedHash,
            shellReplyFactory = { "READY port=notanumber\n" }
        )
        val launcher2 = AdbLauncher(
            adbClient = malformedAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(host = "192.168.1.42", port = 37199)
        )
        try {
            launcher2.start()
            fail("Expected ServerStartFailed for malformed READY")
        } catch (e: SessionErrorException) {
            assertTrue(e.error is SessionError.ServerStartFailed)
            assertTrue(malformedAdb.shellStreams.single().isClosed)
        } finally {
            launcher2.close()
        }
    }

    @Test
    fun `closes shell stream on READY timeout and redacts echoed secret`() = runBlocking {
        val jarFile = createSampleJar()
        val expectedHash = Sha256.hashFileHex(jarFile)
        val secretBytes = ByteArray(ProtocolV2.SECRET_SIZE) { 0x5A }
        val secretHex = Sha256.toLowerHex(secretBytes)
        val hangingAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = expectedHash,
            hangOnReadyWithLine = "echo $secretHex\n"
        )
        val launcher = AdbLauncher(
            adbClient = hangingAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(
                host = "192.168.1.42",
                port = 37199,
                readyTimeoutMs = 150L
            ),
            secretGenerator = { secretBytes.copyOf() }
        )
        try {
            launcher.start()
            fail("Expected timeout ServerStartFailed")
        } catch (e: SessionErrorException) {
            assertTrue(e.error is SessionError.ServerStartFailed)
            assertTrue(e.error.detail?.contains("Timed out") == true)
            assertFalse(
                "Secret must be redacted from timeout error detail",
                e.error.detail?.contains(secretHex) == true
            )
            assertTrue(hangingAdb.shellStreams.single().isClosed)
        } finally {
            launcher.close()
        }
    }

    @Test
    fun `USB transport selects ADB tunnel bound to loopback automatically`() = runBlocking {
        val jarFile = createSampleJar()
        val expectedHash = Sha256.hashFileHex(jarFile)
        val fakeAdb = FakeLaunchAdbClient(
            connectedHost = "192.168.1.42",
            initialRemoteSha256 = expectedHash
        )
        val launcher = AdbLauncher(
            adbClient = fakeAdb,
            jarSource = FileServerJarSource(jarFile),
            config = AdbLauncherConfig(
                host = "usb:001",
                port = 5555,
                transportKind = AdbTransportKind.USB
            )
        )

        val endpoint = launcher.start()
        assertEquals(DataChannelMode.ADB_TUNNEL, endpoint.dataChannelMode)
        assertEquals(AdbLauncher.REASON_USB_TRANSPORT, endpoint.dataChannelReason)
        assertEquals("127.0.0.1", endpoint.host)
        assertNotNull(endpoint.tunnelConnector)
        assertTrue(fakeAdb.openedServices.single().contains("--bind loopback"))
        assertEquals(DataChannelMode.ADB_TUNNEL, launcher.lastDataChannelDecision.value?.mode)
        launcher.close()
    }

    @Test
    fun `Wi-Fi falls back to ADB tunnel and completes AEAD session with SessionEngine`() =
        runBlocking {
            val jarFile = createSampleJar()
            val expectedHash = Sha256.hashFileHex(jarFile)
            val fakeAdb = FakeLaunchAdbClient(
                connectedHost = "192.168.1.42",
                initialRemoteSha256 = expectedHash,
                protocolV2TunnelServer = true
            )
            val metrics = SessionMetrics()
            val launcher = AdbLauncher(
                adbClient = fakeAdb,
                jarSource = FileServerJarSource(jarFile),
                config = AdbLauncherConfig(
                    host = "192.168.1.42",
                    port = 37199,
                    transportKind = AdbTransportKind.WIFI,
                    dataChannelPreference = DataChannelPreference.AUTO
                ),
                onDiagnostics = { cat, msg -> metrics.recordDiagnosticsEvent(cat, msg) }
            )
            val failingDirectConnector = ClientSocketConnector { host, port, _, _ ->
                throw ConnectException("Simulated AP isolation to $host:$port")
            }
            val transport = MirrorClient(
                ioDispatcher = Dispatchers.IO,
                socketConnector = failingDirectConnector
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val engine = SessionEngine(
                serverLauncher = launcher,
                transport = transport,
                parentScope = scope,
                config = SessionConfig(handshakeTimeoutMs = 3_000L, socketReadTimeoutMs = 3_000L),
                lifecycleHooks = object : SessionLifecycleHooks {
                    override fun onDataChannelSelected(mode: DataChannelMode, reason: String?) {
                        metrics.recordDataChannel(mode, reason)
                    }
                }
            )

            try {
                engine.connect()
                val streaming = withTimeout(5_000L) {
                    engine.state.first {
                        (it is SessionState.Streaming && it.deviceInfo != null) ||
                            it is SessionState.Failed
                    }
                }
                assertTunnelFallbackSucceeded(streaming, fakeAdb, metrics)
            } finally {
                engine.disconnect()
                engine.close()
                launcher.close()
                scope.cancel()
            }
        }

    private fun assertTunnelFallbackSucceeded(
        streaming: SessionState,
        fakeAdb: FakeLaunchAdbClient,
        metrics: SessionMetrics
    ) {
        assertTrue("Expected Streaming, got $streaming", streaming is SessionState.Streaming)
        val streamState = streaming as SessionState.Streaming
        assertEquals(DataChannelMode.ADB_TUNNEL, streamState.endpoint.dataChannelMode)
        assertEquals(
            AdbLauncher.REASON_WIFI_DIRECT_FALLBACK,
            streamState.endpoint.dataChannelReason
        )
        assertEquals("M2101K6G", streamState.deviceInfo?.model)

        val shellCommands = fakeAdb.openedServices.filter { it.startsWith("shell:") }
        assertEquals(2, shellCommands.size)
        assertTrue(shellCommands[0].contains("--bind 192.168.1.42"))
        assertTrue(shellCommands[1].contains("--bind loopback"))
        assertTrue(fakeAdb.shellStreams[0].isClosed)
        assertFalse(fakeAdb.shellStreams[1].isClosed)
        assertTrue(fakeAdb.openedServices.any { it.startsWith("tcp:") })

        assertEquals(DataChannelMode.ADB_TUNNEL, metrics.state.value.dataChannelMode)
        assertEquals(
            AdbLauncher.REASON_WIFI_DIRECT_FALLBACK,
            metrics.state.value.dataChannelReason
        )
        assertTrue(
            metrics.diagnosticsLog.snapshot().any {
                it.category == "DATA_CHANNEL" && it.message.contains("ADB_TUNNEL")
            }
        )
    }

    private fun createSampleJar(): File = tempFolder.newFile("velocast-server.jar").apply {
        writeBytes(ByteArray(4096) { idx -> (idx * 17 + 3).toByte() })
    }
}

private class FakeLaunchAdbClient(
    private val connectedHost: String,
    initialRemoteSha256: String?,
    private val corruptPushHash: String? = null,
    private val shellReplyFactory: (
        String
    ) -> String = { "READY port=41234 nonce=deadbeef01020304\n" },
    private val hangOnReadyWithLine: String? = null,
    private val protocolV2TunnelServer: Boolean = false
) : AdbClient {
    val pushCount = AtomicInteger(0)
    val openedServices = CopyOnWriteArrayList<String>()
    val shellStreams = CopyOnWriteArrayList<FakeAdbShellStream>()
    var remoteJarBytes: ByteArray? = null
    var remoteSha256: String? = initialRemoteSha256
    private val nextPort = AtomicInteger(41234)

    @Volatile
    private var lastSecretHex: String? = null

    override var connectedDevice: AdbConnectedDevice? = null
        private set

    override val discovery: AdbDiscovery
        get() = throw UnsupportedOperationException()

    override fun ensureKeyIdentity(alias: String): AdbKeyIdentity =
        throw UnsupportedOperationException()

    override fun recordKnownPairedHost(host: String) = Unit

    override suspend fun pair(
        host: String,
        port: Int,
        code: String,
        keyAlias: String
    ): AdbPairingResult = throw UnsupportedOperationException()

    override suspend fun pairWithQr(
        keyAlias: String,
        timeoutMs: Long,
        onQrPayload: (AdbQrPayload) -> Unit
    ): AdbPairingResult = throw UnsupportedOperationException()

    override suspend fun connect(host: String?, port: Int?, keyAlias: String): AdbConnectedDevice {
        val resolvedHost = host ?: connectedHost
        val resolvedPort = port ?: 37199
        return AdbConnectedDevice(
            host = resolvedHost,
            port = resolvedPort,
            banner = "device::ro.product.model=M2101K6G"
        ).also { connectedDevice = it }
    }

    override suspend fun shell(command: String): AdbShellResult =
        if (command.startsWith("sha256sum ")) {
            val hash = remoteSha256
            if (hash == null) {
                AdbShellResult("", stderr = "sha256sum: missing", exitCode = 1)
            } else {
                AdbShellResult("$hash  /data/local/tmp/velocast-server.jar\n")
            }
        } else {
            AdbShellResult("")
        }

    override suspend fun push(file: File, remotePath: String, mode: Int) {
        pushCount.incrementAndGet()
        val bytes = file.readBytes()
        remoteJarBytes = bytes
        remoteSha256 = corruptPushHash ?: Sha256.hashBytesHex(bytes)
    }

    override suspend fun openStream(service: String): AdbByteStream {
        openedServices += service
        return when {
            service.startsWith("shell:") -> {
                val replyText = if (protocolV2TunnelServer) {
                    "READY port=${nextPort.getAndIncrement()} nonce=0123456789abcdef\n"
                } else {
                    shellReplyFactory(service)
                }
                val stream = FakeAdbShellStream(
                    service = service,
                    readyLineReply = replyText,
                    hangAfterLine = hangOnReadyWithLine,
                    onSecretLine = { lastSecretHex = it }
                )
                shellStreams += stream
                stream
            }

            service.startsWith("tcp:") -> {
                val secretHex = checkNotNull(lastSecretHex) { "No secret piped before tcp tunnel" }
                openProtocolV2TunnelStream(service, decodeHex32(secretHex))
            }

            else -> throw IOException("Unsupported fake service: $service")
        }
    }

    override suspend fun disconnect() {
        connectedDevice = null
    }

    override fun close() {
        connectedDevice = null
    }

    private fun openProtocolV2TunnelStream(service: String, secret: ByteArray): AdbByteStream {
        val clientToServerIn = PipedInputStream(64 * 1024)
        val clientToServerOut = PipedOutputStream(clientToServerIn)
        val serverToClientIn = PipedInputStream(64 * 1024)
        val serverToClientOut = PipedOutputStream(serverToClientIn)
        val closed = AtomicBoolean(false)

        val serverThread = Thread {
            val auth = HkdfAuthenticator(secret, AuthMode.SESSION_SECRET)
            try {
                val serverNonce = ByteArray(ProtocolV2.NONCE_SIZE) { idx -> (idx + 0x20).toByte() }
                val handshake = ServerHandshake(auth, Features.SECURE_CHANNEL, serverNonce)
                val reader = ControlReader(clientToServerIn)
                val writer = FrameWriter(serverToClientOut)
                val packet = ControlPacket()

                if (!reader.read(packet) || packet.type != MessageType.HELLO) return@Thread
                val serverHello = handshake.onHello(reader.payload, 0, packet.payloadLength)
                writer.writeMessage(serverHello)
                serverToClientOut.flush()

                if (!reader.read(packet) || packet.type != MessageType.AUTH_PROOF) return@Thread
                val serverProof = handshake.onClientProof(reader.payload, 0, packet.payloadLength)
                writer.writeMessage(serverProof)
                serverToClientOut.flush()

                val keys = handshake.deriveSessionKeys()!!
                writer.cipher = keys.newServerOutboundCipher()
                reader.cipher = keys.newServerInboundCipher()
                keys.close()
                writer.phase = Phase.SESSION
                reader.phase = Phase.SESSION

                writer.writeMessage(
                    DeviceInfo(
                        sdkInt = 36,
                        launchMode = LaunchMode.SHELL,
                        display = DisplayGeometry(1080, 2400, 0),
                        manufacturer = "Xiaomi",
                        model = "M2101K6G"
                    )
                )
                serverToClientOut.flush()

                while (!closed.get() && reader.read(packet)) {
                    if (packet.type == MessageType.BYE) break
                }
            } catch (_: IOException) {
                // Tunnel closed on teardown.
            } finally {
                auth.close()
            }
        }.apply {
            isDaemon = true
            start()
        }

        return object : AdbByteStream {
            override val service: String = service
            override val inputStream: InputStream = serverToClientIn
            override val outputStream: OutputStream = clientToServerOut
            override val isClosed: Boolean
                get() = closed.get()

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { clientToServerOut.close() }
                    runCatching { serverToClientIn.close() }
                    runCatching { serverToClientOut.close() }
                    runCatching { clientToServerIn.close() }
                    serverThread.interrupt()
                }
            }
        }
    }

    private fun decodeHex32(hex: String): ByteArray = ByteArray(ProtocolV2.SECRET_SIZE) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private class FakeAdbShellStream(
    override val service: String,
    private val readyLineReply: String,
    private val hangAfterLine: String? = null,
    private val onSecretLine: (String) -> Unit = {}
) : AdbByteStream {
    private val closed = AtomicBoolean(false)
    private val pipedIn = PipedInputStream(8 * 1024)
    private val pipedOut = PipedOutputStream(pipedIn)
    private val stdinBuf = ByteArrayOutputStream()

    override val inputStream: InputStream = pipedIn
    override val outputStream: OutputStream = object : OutputStream() {
        private var replied = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            stdinBuf.write(b, off, len)
            val text = stdinBuf.toString(Charsets.UTF_8.name())
            if (!replied && text.contains('\n')) {
                replied = true
                onSecretLine(text.trim())
                if (hangAfterLine != null) {
                    pipedOut.write(hangAfterLine.toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                } else {
                    pipedOut.write(readyLineReply.toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                }
            }
        }
    }

    override val isClosed: Boolean
        get() = closed.get()

    fun writtenStdinText(): String = stdinBuf.toString(Charsets.UTF_8.name())

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
        }
    }
}
