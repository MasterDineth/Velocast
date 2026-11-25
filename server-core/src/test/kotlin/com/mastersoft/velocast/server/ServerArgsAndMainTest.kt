package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.AuthRole
import com.mastersoft.velocast.protocol.ClientHandshake
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Transcript
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerArgsAndMainTest {
    @Test
    fun parsesCliArgumentsCorrectly() {
        val parsed = ServerArgs.parse(
            arrayOf(
                "--port", "0",
                "--bind", "loopback",
                "--state-dir", "build/test-state",
                "--secret-from-stdin",
                "--log-level", "debug"
            )
        )
        assertEquals(0, parsed.port)
        assertEquals(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), parsed.bindAddress)
        assertEquals("test-state", parsed.stateDir?.fileName?.toString())
        assertTrue(parsed.secretFromStdin)
        assertEquals(LogLevel.DEBUG, parsed.logLevel)

        val ipv6 = ServerArgs.parse(arrayOf("--bind", "::1"))
        assertEquals(InetAddress.getByName("::1"), ipv6.bindAddress)
    }

    @Test
    fun rejectsInvalidCliArguments() {
        val invalidCases = listOf(
            arrayOf("--unknown"),
            arrayOf("--port"),
            arrayOf("--port", "70000"),
            arrayOf("--port", "-1"),
            arrayOf("--bind", "localhost"),
            arrayOf("--bind", "999.0.0.1"),
            arrayOf("--state-dir", "   "),
            arrayOf("--log-level", "VERBOSE")
        )
        for (bad in invalidCases) {
            try {
                ServerArgs.parse(bad)
                fail("Expected IllegalArgumentException for ${bad.joinToString(" ")}")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun secretReaderReadsSingleLineWithoutWaitingForEof() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val hexSecret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        pipeOut.write("$hexSecret\n".toByteArray(Charsets.US_ASCII))
        pipeOut.flush()

        // Keep pipeOut open (no EOF) to verify SecretReader does not block.
        val decoded = SecretReader.readHexLine(pipeIn)
        assertEquals(32, decoded.size)
        assertEquals(0x00.toByte(), decoded[0])
        assertEquals(0x1f.toByte(), decoded[31])
        pipeOut.close()
    }

    @Test
    fun hmacAuthenticatorMatchesGoldenVectorAndRedactsToString() {
        val key = ByteArray(32) { it.toByte() }
        val auth = HmacSha256Authenticator(key)
        assertFalse(auth.toString().contains("00"))

        val hello = byteArrayOf(
            0x56, 0x4C, 0x43, 0x32, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
        )
        val serverHello = byteArrayOf(
            0x00, 0x02, 0x00, 0x00, 0x00, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
            0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
        )
        val transcript = Transcript.build(AuthRole.CLIENT, hello, serverHello)
        val proof = auth.computeProof(transcript)
        assertEquals(ProtocolV2.PROOF_SIZE, proof.size)
        assertTrue(auth.verifyProof(transcript, proof))

        val tampered = proof.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(auth.verifyProof(transcript, tampered))
        assertFalse(auth.verifyProof(transcript, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun serverRunnerPrintsReadyLineAndCompletesLoopbackSession() {
        val stateDir = Files.createTempDirectory("velocast-runner-test")
        val stdoutBytes = ByteArrayOutputStream()
        val readyLatch = CountDownLatch(1)
        val stdoutCapture = object : PrintStream(stdoutBytes) {
            override fun println(x: String?) {
                super.println(x)
                if (x?.startsWith("READY ") == true) {
                    readyLatch.countDown()
                }
            }
        }
        val secret = ByteArray(32) { (0x40 + it).toByte() }
        val logger = Logger(
            minLevel = LogLevel.DEBUG,
            logFile = stateDir.resolve("server.log"),
            stderr = PrintStream(ByteArrayOutputStream())
        )
        val clock = FakeServerClock(5_000L)
        val env = FakeSessionEnvironment(clock, logger)
        val runner = ServerRunner(
            args = ServerArgs(
                port = 0,
                bindAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                stateDir = stateDir,
                secretFromStdin = true,
                logLevel = LogLevel.DEBUG
            ),
            authenticator = HmacSha256Authenticator(secret),
            environment = env,
            clock = clock,
            logger = logger,
            stdout = stdoutCapture
        )

        val serverThread = Thread({ runner.run() }, "test-server-runner").apply { start() }
        try {
            assertTrue("Timed out waiting for READY", readyLatch.await(5, TimeUnit.SECONDS))
            val readyLine = stdoutBytes.toString(Charsets.UTF_8.name()).trim()
            val match = Regex("^READY port=(\\d+) nonce=([0-9a-f]{32})$").matchEntire(readyLine)
            assertTrue("Unexpected READY format: $readyLine", match != null)
            val boundPort = match!!.groupValues[1].toInt()
            assertTrue(boundPort in 1..65_535)
            verifyLoopbackClientSession(boundPort, secret, env.sampleDeviceInfo)
        } finally {
            runner.close()
            serverThread.join(3_000L)
            logger.close()
            stateDir.toFile().deleteRecursively()
        }
        assertArrayEquals(ByteArray(0), ByteArray(0))
    }

    private fun verifyLoopbackClientSession(
        boundPort: Int,
        secret: ByteArray,
        expectedDeviceInfo: DeviceInfo
    ) {
        Socket("127.0.0.1", boundPort).use { clientSocket ->
            clientSocket.soTimeout = 5_000
            val cw = ControlWriter(clientSocket.getOutputStream())
            val fr = FrameReader(clientSocket.getInputStream())
            val client = ClientHandshake(
                HmacSha256Authenticator(secret),
                0,
                TestClientDriver.CLIENT_NONCE
            )

            cw.writePayload(client.hello())
            clientSocket.getOutputStream().flush()

            val h = FrameHeader()
            assertTrue(fr.readHeader(h))
            fr.readPayload(h)
            cw.writePayload(client.onServerHello(fr.payload, 0, h.length))
            clientSocket.getOutputStream().flush()

            assertTrue(fr.readHeader(h))
            fr.readPayload(h)
            client.onServerProof(fr.payload, 0, h.length)

            cw.phase = Phase.SESSION
            fr.phase = Phase.SESSION

            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.DEVICE_INFO, h.type)
            fr.readPayload(h)
            assertEquals(expectedDeviceInfo, DeviceInfo.decode(fr.payload, 0, h.length))

            cw.ping(99)
            clientSocket.getOutputStream().flush()
            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.PONG, h.type)
            fr.readPayload(h)
            val pong = Pong.decode(fr.payload, 0, h.length)
            assertEquals(99, pong.token)
            assertTrue(pong.serverUs >= 0L)

            cw.bye()
            clientSocket.getOutputStream().flush()
            assertTrue(fr.readHeader(h))
            assertEquals(MessageType.BYE, h.type)
        }
    }
}
