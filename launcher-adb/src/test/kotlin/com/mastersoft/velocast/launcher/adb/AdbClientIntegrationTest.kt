package com.mastersoft.velocast.launcher.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PublicKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbClientIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val closeables = ArrayList<Closeable>()

    @After
    fun tearDown() {
        for (c in closeables.asReversed()) {
            try {
                c.close()
            } catch (_: IOException) {
            }
        }
        closeables.clear()
    }

    @Test
    fun `rejects unauthenticated connect with NotPaired and wrong code with WrongPairingCode`() =
        runBlocking {
            val authorizedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val env = createTestClientEnv()
            val connectServer = FakeTlsAdbdServer(authorizedKeys).also {
                it.start()
                closeables += it
            }

            try {
                env.client.connect("127.0.0.1", connectServer.port)
                fail("Expected NotPaired before pairing")
            } catch (e: AdbClientException) {
                assertTrue(
                    "Expected NotPaired, got ${e.failure}",
                    e.failure is AdbFailure.NotPaired
                )
            }

            val pairingServer = FakeAdbPairingServer("482915") { authorizedKeys.add(it) }.also {
                it.start()
                closeables += it
            }
            try {
                env.client.pair("127.0.0.1", pairingServer.port, "000000")
                fail("Expected WrongPairingCode")
            } catch (e: AdbClientException) {
                assertTrue(
                    "Expected WrongPairingCode, got ${e.failure}",
                    e.failure is AdbFailure.WrongPairingCode
                )
            }
        }

    @Test
    fun `pairs with 6-digit code, connects via STLS, and runs shell push and openStream`() =
        runBlocking {
            val authorizedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
            val env = createTestClientEnv()
            val connectServer = FakeTlsAdbdServer(authorizedKeys).also {
                it.start()
                closeables += it
            }
            val pairingServer = FakeAdbPairingServer("482915") { authorizedKeys.add(it) }.also {
                it.start()
                closeables += it
            }

            val pairResult = env.client.pair("127.0.0.1", pairingServer.port, "482915")
            assertTrue(pairingServer.awaitAuthorized())
            assertEquals("adb-velocast-test-guid", pairResult.peerGuid)
            assertEquals(1, authorizedKeys.size)

            val connected = env.client.connect("127.0.0.1", connectServer.port)
            assertTrue(connected.isTls)
            assertEquals("M2101K6G", connected.model)

            val shellRes = env.client.shell("echo velocast-ready")
            assertEquals("out:echo velocast-ready\n", shellRes.stdout)
            assertEquals(0, shellRes.exitCode)

            val localJar = tempFolder.newFile("velocast-server.jar")
            val jarBytes = ByteArray(150_000) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
            localJar.writeBytes(jarBytes)
            env.client.push(localJar, "/data/local/tmp/velocast-server.jar")
            assertArrayEquals(
                jarBytes,
                connectServer.pushedFiles["/data/local/tmp/velocast-server.jar"]
            )

            env.client.openStream("shell:app_process --secret-from-stdin").use { stream ->
                stream.outputStream.write("deadbeef\n".toByteArray(Charsets.UTF_8))
                stream.outputStream.flush()
                val line = stream.inputStream.bufferedReader(Charsets.UTF_8).readLine()
                assertEquals("READY 27183 (stdin=deadbeef)", line)
            }
            env.client.disconnect()
        }

    @Test
    fun `recovers from wireless debugging port change and detects AuthExpired`() = runBlocking {
        val authorizedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
        val env = createTestClientEnv()
        val identity = env.client.ensureKeyIdentity()
        authorizedKeys.add(identity.publicKey)
        env.client.recordKnownPairedHost("127.0.0.1")

        val server1 = FakeTlsAdbdServer(authorizedKeys).also {
            it.start()
            closeables += it
        }
        val stalePort = server1.port
        server1.close()

        val server2 = FakeTlsAdbdServer(authorizedKeys).also {
            it.start()
            closeables += it
        }
        val refresher = async {
            delay(150L)
            env.nsdBackend.emitResolved(
                DiscoveredAdbService(
                    serviceName = "adb-6f8d1516-toggled",
                    serviceType = AdbServiceType.TLS_CONNECT,
                    host = "127.0.0.1",
                    port = server2.port
                )
            )
        }
        val reconnected = env.client.connect("127.0.0.1", stalePort)
        refresher.await()
        assertEquals(server2.port, reconnected.port)
        env.client.disconnect()

        authorizedKeys.clear()
        try {
            env.client.connect("127.0.0.1", server2.port)
            fail("Expected AuthExpired after key revocation")
        } catch (e: AdbClientException) {
            assertTrue(
                "Expected AuthExpired, got ${e.failure}",
                e.failure is AdbFailure.AuthExpired
            )
        }
    }

    @Test
    fun `pairs via QR payload when old phone advertises matching pairing service`() = runBlocking {
        val authorizedKeys = Collections.synchronizedSet(LinkedHashSet<PublicKey>())
        val env = createTestClientEnv()
        val connectServer = FakeTlsAdbdServer(authorizedKeys).also {
            it.start()
            closeables += it
        }
        var qrServerPort = -1
        var qrPairingServer: FakeAdbPairingServer? = null
        val qrResult = env.client.pairWithQr(timeoutMs = 5_000L) { qrPayload ->
            val server = FakeAdbPairingServer(qrPayload.password) {
                authorizedKeys.add(it)
            }.also {
                it.start()
                closeables += it
            }
            qrPairingServer = server
            qrServerPort = server.port
            env.nsdBackend.emitResolved(
                DiscoveredAdbService(
                    serviceName = qrPayload.serviceName,
                    serviceType = AdbServiceType.TLS_PAIRING,
                    host = "127.0.0.1",
                    port = server.port
                )
            )
        }
        assertTrue(qrPairingServer!!.awaitAuthorized())
        assertEquals(qrServerPort, qrResult.port)
        assertTrue(env.client.connect("127.0.0.1", connectServer.port).isTls)
    }

    private fun createTestClientEnv(): TestClientEnv {
        val nsdBackend = FakeNsdBackend()
        val errorMapper = AdbErrorMapper { LocalNetworkSnapshot(isLanAvailable = true) }
        val discovery = AdbMdnsDiscovery(nsdBackend, errorMapper)
        val client = InHouseAdbClient(
            keyStore = InMemoryAdbKeyStore(),
            discovery = discovery,
            errorMapper = errorMapper,
            config = AdbClientConfig(
                connectTimeoutMs = 3_000,
                handshakeReadTimeoutMs = 3_000,
                discoveryTimeoutMs = 3_000L,
                portRefreshDiscoveryTimeoutMs = 2_000L
            )
        ).also { closeables += it }
        return TestClientEnv(client, nsdBackend)
    }

    private class TestClientEnv(val client: InHouseAdbClient, val nsdBackend: FakeNsdBackend)
}

private class FakeAdbPairingServer(
    private val expectedCode: String,
    private val onAuthorized: (PublicKey) -> Unit
) : Closeable {
    private val serverIdentity = AdbCertificateGenerator.generate("fake_adbd_pair")
    private val sslContext = AdbTlsSupport.createSslContext(serverIdentity)
    private val serverSocket = (
        sslContext.serverSocketFactory.createServerSocket(
            0,
            4,
            InetAddress.getByName("127.0.0.1")
        ) as SSLServerSocket
        ).apply {
        enabledProtocols = arrayOf(AdbTlsSupport.TLS_PROTOCOL)
        wantClientAuth = true
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val authorizedLatch = CountDownLatch(1)

    val port: Int get() = serverSocket.localPort

    fun awaitAuthorized(timeoutMs: Long = 3_000L): Boolean =
        authorizedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun start() {
        executor.execute {
            try {
                val clientSocket = serverSocket.accept() as SSLSocket
                clientSocket.soTimeout = 3_000
                clientSocket.startHandshake()
                val peerCerts = clientSocket.session.peerCertificates
                val peerData = AdbPairingProtocol.acceptAsServer(clientSocket, expectedCode)
                if (peerData.isNotEmpty() && peerCerts.isNotEmpty()) {
                    onAuthorized(peerCerts[0].publicKey)
                    authorizedLatch.countDown()
                }
            } catch (_: Exception) {
                // Expected on wrong code or teardown.
            }
        }
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        executor.shutdownNow()
    }
}

private class FakeTlsAdbdServer(private val authorizedClientKeys: Set<PublicKey>) : Closeable {
    private val serverIdentity = AdbCertificateGenerator.generate("fake_adbd_tls")
    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val pushedFiles = ConcurrentHashMap<String, ByteArray>()

    val port: Int get() = serverSocket.localPort

    fun start() {
        executor.execute {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                executor.execute { handleConnection(client) }
            }
        }
    }

    private fun handleConnection(plainSocket: Socket) {
        plainSocket.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 3_000
                val plainIn = DataInputStream(sock.getInputStream())
                val plainOut = DataOutputStream(sock.getOutputStream())

                val cnxn = readRawPacket(plainIn)
                if (cnxn.command != AdbPacket.A_CNXN) return
                writeRawPacket(
                    plainOut,
                    AdbPacket(AdbPacket.A_STLS, AdbPacket.A_STLS_VERSION, 0)
                )
                val stlsReply = readRawPacket(plainIn)
                if (stlsReply.command != AdbPacket.A_STLS) return

                val sslSocket = upgradeAcceptedSocket(sock)
                val tlsIn = DataInputStream(sslSocket.inputStream)
                val tlsOut = DataOutputStream(sslSocket.outputStream)
                sendDeviceCnxnBanner(tlsOut)
                serveStreams(tlsIn, tlsOut)
            } catch (_: Exception) {
                // Closed or unauthorized client rejected during TLS handshake.
            }
        }
    }

    private fun upgradeAcceptedSocket(sock: Socket): SSLSocket {
        val sslContext = AdbTlsSupport.createSslContext(serverIdentity) { chain ->
            !chain.isNullOrEmpty() && chain[0].publicKey in authorizedClientKeys
        }
        val sslSocket = sslContext.socketFactory
            .createSocket(sock, "127.0.0.1", sock.port, true) as SSLSocket
        sslSocket.useClientMode = false
        sslSocket.needClientAuth = true
        sslSocket.enabledProtocols = arrayOf(AdbTlsSupport.TLS_PROTOCOL)
        sslSocket.startHandshake()
        sslSocket.soTimeout = 0
        return sslSocket
    }

    private fun sendDeviceCnxnBanner(tlsOut: DataOutputStream) {
        val banner = (
            "device::ro.product.name=sweet;ro.product.model=M2101K6G;" +
                "ro.product.device=sweet;features=shell_v2,cmd,stat_v2\u0000"
            ).toByteArray(Charsets.UTF_8)
        writeRawPacket(
            tlsOut,
            AdbPacket(
                AdbPacket.A_CNXN,
                AdbPacket.A_VERSION_SKIP_CHECKSUM,
                AdbPacket.DEFAULT_MAX_DATA,
                banner
            )
        )
    }

    private fun serveStreams(input: DataInputStream, output: DataOutputStream) {
        var nextRemoteId = 100
        val syncBuffers = HashMap<Int, SyncUploadState>()
        val interactiveShells = HashSet<Int>()

        while (true) {
            val pkt = readRawPacket(input)
            when (pkt.command) {
                AdbPacket.A_OPEN -> {
                    val remoteId = ++nextRemoteId
                    handleStreamOpen(pkt, remoteId, output, syncBuffers, interactiveShells)
                }

                AdbPacket.A_WRTE -> {
                    handleStreamWrite(pkt, output, syncBuffers, interactiveShells)
                }

                AdbPacket.A_CLSE -> {
                    syncBuffers.remove(pkt.arg1)
                    interactiveShells.remove(pkt.arg1)
                }
            }
        }
    }

    private fun handleStreamOpen(
        pkt: AdbPacket,
        remoteId: Int,
        output: DataOutputStream,
        syncBuffers: MutableMap<Int, SyncUploadState>,
        interactiveShells: MutableSet<Int>
    ) {
        val clientLocalId = pkt.arg0
        val service = String(
            pkt.payload,
            0,
            (pkt.payload.size - 1).coerceAtLeast(0),
            Charsets.UTF_8
        )
        writeRawPacket(output, AdbPacket(AdbPacket.A_OKAY, remoteId, clientLocalId))
        when {
            service.startsWith("shell,v2,raw:") -> {
                val cmd = service.removePrefix("shell,v2,raw:")
                val stdoutBytes = "out:$cmd\n".toByteArray(Charsets.UTF_8)
                val shellPayload = ByteBuffer.allocate(5 + stdoutBytes.size + 6)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(1.toByte())
                    .putInt(stdoutBytes.size)
                    .put(stdoutBytes)
                    .put(3.toByte())
                    .putInt(1)
                    .put(0.toByte())
                    .array()
                writeRawPacket(
                    output,
                    AdbPacket(AdbPacket.A_WRTE, remoteId, clientLocalId, shellPayload)
                )
                writeRawPacket(output, AdbPacket(AdbPacket.A_CLSE, remoteId, clientLocalId))
            }

            service == "sync:" -> syncBuffers[remoteId] = SyncUploadState(clientLocalId)

            service.startsWith("shell:app_process") -> interactiveShells.add(remoteId)
        }
    }

    private fun handleStreamWrite(
        pkt: AdbPacket,
        output: DataOutputStream,
        syncBuffers: Map<Int, SyncUploadState>,
        interactiveShells: Set<Int>
    ) {
        val clientLocalId = pkt.arg0
        val remoteId = pkt.arg1
        writeRawPacket(output, AdbPacket(AdbPacket.A_OKAY, remoteId, clientLocalId))
        if (remoteId in interactiveShells) {
            val secretLine = String(pkt.payload, Charsets.UTF_8).trim()
            val ready = "READY 27183 (stdin=$secretLine)\n".toByteArray(Charsets.UTF_8)
            writeRawPacket(output, AdbPacket(AdbPacket.A_WRTE, remoteId, clientLocalId, ready))
        } else {
            syncBuffers[remoteId]?.onChunk(pkt.payload, output, remoteId)
        }
    }

    private inner class SyncUploadState(val clientLocalId: Int) {
        private val rawStream = ByteArrayOutputStream()
        private var targetPath: String? = null
        private val fileContent = ByteArrayOutputStream()

        fun onChunk(bytes: ByteArray, output: DataOutputStream, remoteId: Int) {
            rawStream.write(bytes)
            val all = rawStream.toByteArray()
            var cursor = 0
            var nextCursor = tryConsumeNextFrame(all, cursor, output, remoteId)
            while (nextCursor != null) {
                cursor = nextCursor
                nextCursor = tryConsumeNextFrame(all, cursor, output, remoteId)
            }
            val remaining = all.copyOfRange(cursor, all.size)
            rawStream.reset()
            rawStream.write(remaining)
        }

        private fun tryConsumeNextFrame(
            all: ByteArray,
            cursor: Int,
            output: DataOutputStream,
            remoteId: Int
        ): Int? {
            if (cursor + 8 > all.size) return null
            val id = String(all, cursor, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(all, cursor + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            return when (id) {
                "SEND" -> if (cursor + 8 + len <= all.size) {
                    val spec = String(all, cursor + 8, len, Charsets.UTF_8)
                    targetPath = spec.substringBeforeLast(',')
                    fileContent.reset()
                    cursor + 8 + len
                } else {
                    null
                }

                "DATA" -> if (cursor + 8 + len <= all.size) {
                    fileContent.write(all, cursor + 8, len)
                    cursor + 8 + len
                } else {
                    null
                }

                "DONE" -> {
                    targetPath?.let { pushedFiles[it] = fileContent.toByteArray() }
                    val okay = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .put("OKAY".toByteArray(Charsets.US_ASCII))
                        .putInt(0)
                        .array()
                    writeRawPacket(
                        output,
                        AdbPacket(AdbPacket.A_WRTE, remoteId, clientLocalId, okay)
                    )
                    cursor + 8
                }

                "QUIT" -> {
                    writeRawPacket(output, AdbPacket(AdbPacket.A_CLSE, remoteId, clientLocalId))
                    cursor + 8
                }

                else -> null
            }
        }
    }

    private fun readRawPacket(input: DataInputStream): AdbPacket {
        val hdrBytes = ByteArray(AdbPacket.HEADER_BYTES)
        input.readFully(hdrBytes)
        val hdr = AdbPacket.decodeHeader(hdrBytes)
        val payload = ByteArray(hdr.payloadLength).also {
            if (hdr.payloadLength > 0) input.readFully(it)
        }
        return AdbPacket(hdr.command, hdr.arg0, hdr.arg1, payload)
    }

    private fun writeRawPacket(output: DataOutputStream, packet: AdbPacket) {
        synchronized(output) {
            output.write(packet.encodeHeader(skipChecksum = true))
            if (packet.payload.isNotEmpty()) {
                output.write(packet.payload)
            }
            output.flush()
        }
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        executor.shutdownNow()
    }
}
