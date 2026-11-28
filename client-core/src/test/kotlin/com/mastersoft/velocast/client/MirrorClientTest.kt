package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.HkdfAuthenticator
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import com.mastersoft.velocast.protocol.testing.HmacTestAuthenticator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorClientTest {

    @Test
    fun `handshakes, enforces session phase gate for pipelined frames, and supports reuse`() =
        runBlocking {
            val secret = ByteArray(32) { (it + 3).toByte() }
            val hkdfAuth = HkdfAuthenticator(secret)
            val legacyTestAuth = HmacTestAuthenticator(secret)
            val client = MirrorClient(ioDispatcher = Dispatchers.IO)

            verifySingleRound(client, hkdfAuth, round = 0)
            verifySingleRound(client, hkdfAuth, round = 1)
            verifySingleRound(client, legacyTestAuth, round = 2)
            hkdfAuth.close()
        }

    private suspend fun verifySingleRound(client: MirrorClient, auth: Authenticator, round: Int) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverError = arrayOfNulls<Throwable>(1)
            val serverThread = Thread {
                try {
                    runServerSession(server, auth, round)
                } catch (t: Throwable) {
                    serverError[0] = t
                }
            }.apply { start() }

            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.IO + scopeJob)
            val endpoint = ServerEndpoint("127.0.0.1", server.localPort, auth)

            try {
                client.connect(endpoint, readTimeoutMs = 2_000L, scope = scope)
                SessionHandshakeRunner.execute(
                    transport = client,
                    endpoint = endpoint,
                    config = SessionConfig()
                ) { ByteArray(ProtocolV2.NONCE_SIZE) { it.toByte() } }

                val infoFrame = withTimeout(2_000L) { client.readFrame() }
                assertNotNull(infoFrame)
                assertEquals(MessageType.DEVICE_INFO, infoFrame!!.type)
                val info = DeviceInfo.decode(infoFrame.payload, 0, infoFrame.payload.size)
                assertEquals("Pixel", info.model)

                val videoFrame = withTimeout(2_000L) { client.readFrame() }
                assertNotNull(videoFrame)
                assertEquals(MessageType.VIDEO, videoFrame!!.type)
                assertTrue(videoFrame.isKeyframe)

                client.writeControl(ControlCommand.Ping(token = 42 + round))
                val pongFrame = withTimeout(2_000L) { client.readFrame() }
                assertNotNull(pongFrame)
                assertEquals(MessageType.PONG, pongFrame!!.type)
                val pong = Pong.decode(pongFrame.payload, 0, pongFrame.payload.size)
                assertEquals(42 + round, pong.token)

                client.writeControl(ControlCommand.Bye(null))
            } finally {
                withContext(NonCancellable) {
                    client.close()
                    scopeJob.cancelAndJoin()
                }
            }

            serverThread.join(2_000L)
            if (serverError[0] != null) throw serverError[0]!!
            assertEquals(0, scopeJob.children.count { it.isActive })
        }
    }

    @Test
    fun `socket read timeout raises SocketTimeoutException`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val connectedLatch = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val serverThread = Thread {
                server.accept().use {
                    connectedLatch.countDown()
                    releaseServer.await(2, TimeUnit.SECONDS)
                }
            }.apply { start() }

            val client = MirrorClient(ioDispatcher = Dispatchers.IO)
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.IO + scopeJob)
            try {
                val endpoint = ServerEndpoint(
                    "127.0.0.1",
                    server.localPort,
                    HmacTestAuthenticator(ByteArray(32))
                )
                client.connect(endpoint, readTimeoutMs = 100L, scope = scope)
                assertTrue(connectedLatch.await(2, TimeUnit.SECONDS))

                var caught: SocketTimeoutException? = null
                try {
                    client.readFrame()
                } catch (e: SocketTimeoutException) {
                    caught = e
                }
                assertNotNull(caught)
            } finally {
                releaseServer.countDown()
                withContext(NonCancellable) {
                    client.close()
                    scopeJob.cancelAndJoin()
                }
                serverThread.join(2_000L)
            }
        }
    }

    @Test
    fun `cancelling caller scope unblocks silent socket with zero leaked coroutines`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val releaseServer = CountDownLatch(1)
                val serverThread = Thread {
                    server.accept().use {
                        releaseServer.await(2, TimeUnit.SECONDS)
                    }
                }.apply { start() }

                val client = MirrorClient(ioDispatcher = Dispatchers.IO)
                val scopeJob = SupervisorJob()
                val scope = CoroutineScope(Dispatchers.IO + scopeJob)
                val endpoint = ServerEndpoint(
                    "127.0.0.1",
                    server.localPort,
                    HmacTestAuthenticator(ByteArray(32))
                )

                client.connect(endpoint, readTimeoutMs = 5_000L, scope = scope)
                val readerCaller = scope.launch {
                    try {
                        client.readFrame()
                    } catch (_: CancellationException) {
                        // Expected on cancellation
                    }
                }

                scope.cancel()
                withTimeout(2_000L) {
                    readerCaller.join()
                    scopeJob.children.forEach { it.join() }
                }
                assertEquals(0, scopeJob.children.count { it.isActive })

                releaseServer.countDown()
                client.close()
                serverThread.join(2_000L)
            }
        }

    private fun runServerSession(server: ServerSocket, auth: Authenticator, round: Int) {
        server.accept().use { socket ->
            val reader = ControlReader(socket.getInputStream())
            val writer = FrameWriter(socket.getOutputStream())
            val packet = ControlPacket()
            val serverHandshake = ServerHandshake(
                authenticator = auth,
                capabilities = SessionConfig.DEFAULT_WANTED_FEATURES,
                serverNonce = ByteArray(ProtocolV2.NONCE_SIZE) { (it + 7).toByte() }
            )

            assertTrue(reader.read(packet))
            assertEquals(MessageType.HELLO, packet.type)
            writer.writeMessage(serverHandshake.onHello(reader.payload, 0, packet.payloadLength))

            assertTrue(reader.read(packet))
            assertEquals(MessageType.AUTH_PROOF, packet.type)
            val serverProof = serverHandshake.onClientProof(reader.payload, 0, packet.payloadLength)
            writer.writeMessage(serverProof)

            val sessionKeys = serverHandshake.deriveSessionKeys()
            if (sessionKeys != null) {
                try {
                    reader.cipher = sessionKeys.newServerInboundCipher()
                    writer.cipher = sessionKeys.newServerOutboundCipher()
                } finally {
                    sessionKeys.close()
                }
            }

            // Switch immediately to SESSION and pipeline DEVICE_INFO + VIDEO before waiting
            reader.phase = Phase.SESSION
            writer.phase = Phase.SESSION
            writer.writeMessage(
                DeviceInfo(34, LaunchMode.SHELL, DisplayGeometry(1080, 2400, 0), "Google", "Pixel")
            )
            val videoHeader = FrameHeader().apply {
                type = MessageType.VIDEO
                flags = FrameFlags.KEYFRAME
                seq = 1
                ptsUs = 1_000L
                captureUs = 900L
            }
            writer.write(videoHeader, byteArrayOf(0, 0, 0, 1), 0, 4)

            assertTrue(reader.read(packet))
            assertEquals(MessageType.PING, packet.type)
            assertEquals(42 + round, packet.param)
            writer.writeMessage(Pong(packet.param, serverUs = 5_000L))

            assertTrue(reader.read(packet))
            assertEquals(MessageType.BYE, packet.type)
        }
    }
}
