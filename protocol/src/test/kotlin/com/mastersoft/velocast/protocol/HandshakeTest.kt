package com.mastersoft.velocast.protocol

import com.mastersoft.velocast.protocol.testing.HmacTestAuthenticator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class HandshakeTest {
    private val key = VectorSupport.hexToBytes(
        "000102030405060708090a0b0c0d0e0f" + "101112131415161718191a1b1c1d1e1f"
    )
    private val clientNonce = ByteArray(16) { it.toByte() }
    private val serverNonce = ByteArray(16) { (0x10 + it).toByte() }
    private val geometry = DisplayGeometry(1080, 2400, 0)
    private val device = DeviceInfo(35, LaunchMode.SHELL, geometry, "Acme", "X1")

    private fun client(secret: ByteArray = key, features: Int = 3): ClientHandshake =
        ClientHandshake(HmacTestAuthenticator(secret), features, clientNonce)

    private fun server(secret: ByteArray = key, capabilities: Int = 0x0F): ServerHandshake {
        val auth = HmacTestAuthenticator(secret)
        return ServerHandshake(auth, capabilities, serverNonce, device)
    }

    private fun expect(code: ErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
            return
        }
        fail("expected $code")
    }

    private fun hex(s: String) = VectorSupport.hexToBytes(s)

    @Test
    fun goldenHandshakeMatchesTheIndependentPythonComputation() {
        val c = client()
        val s = server()
        val hello = c.hello().encode()
        assertArrayEquals(hex("564c4332000200000003000102030405060708090a0b0c0d0e0f"), hello)
        val serverHello = s.onHello(hello, 0, hello.size).encode()
        assertArrayEquals(
            hex("00020000000f101112131415161718191a1b1c1d1e1f00230104380960000441636d65025831"),
            serverHello
        )
        val clientTranscript = Transcript.build(AuthRole.CLIENT, hello, serverHello)
        assertArrayEquals(
            hex(
                "564c433220636c69656e742070726f6f66" +
                    "564c4332000200000003000102030405060708090a0b0c0d0e0f" +
                    "00020000000f101112131415161718191a1b1c1d1e1f00230104380960000441636d65025831"
            ),
            clientTranscript
        )
        val clientProof = c.onServerHello(serverHello, 0, serverHello.size)
        val expectedClientProof = hex(
            "0f94501c99a627e0c0835f7ae1cd7490" + "1cbe09dc0cf4f15186aa0ab7d68c4bec"
        )
        assertArrayEquals(expectedClientProof, clientProof.proof)
        val wire = clientProof.encode()
        val serverProof = s.onClientProof(wire, 0, wire.size)
        val expectedServerProof = hex(
            "da5f592f8c3c4caa9cbd4876c3a67b5b" + "a6c5f42afa31e7e3d2e57000e496c3b2"
        )
        assertArrayEquals(expectedServerProof, serverProof.proof)
        val back = serverProof.encode()
        c.onServerProof(back, 0, back.size)
        assertEquals(HandshakeState.ESTABLISHED, c.state)
        assertEquals(HandshakeState.ESTABLISHED, s.state)
        assertEquals(2, c.negotiatedVersion)
        assertEquals(2, s.negotiatedVersion)
        assertEquals(3, c.negotiatedFeatures)
        assertEquals(3, s.negotiatedFeatures)
        assertNotNull(c.serverHello)
        assertNotNull(s.clientHello)
    }

    @Test
    fun fullHandshakeOverStreamsThenSessionTraffic() {
        val c = client()
        val s = server()
        val toServer = ByteArrayOutputStream()
        val toClient = ByteArrayOutputStream()
        val cw = ControlWriter(toServer)
        val sw = FrameWriter(toClient)

        cw.writePayload(c.hello())
        val sr = ControlReader(ByteArrayInputStream(toServer.toByteArray()))
        val p = ControlPacket()
        check(sr.read(p))
        sw.writeMessage(s.onHello(sr.payload, 0, p.payloadLength))

        val cr = FrameReader(ByteArrayInputStream(toClient.toByteArray()))
        val h = FrameHeader()
        check(cr.readHeader(h))
        cr.readPayload(h)
        cw.writePayload(c.onServerHello(cr.payload, 0, h.length))

        val rest = toServer.toByteArray().copyOfRange(16 + 26, toServer.size())
        val sr2 = ControlReader(ByteArrayInputStream(rest))
        check(sr2.read(p))
        assertEquals(MessageType.AUTH_PROOF, p.type)
        sw.writeMessage(s.onClientProof(sr2.payload, 0, p.payloadLength))

        val all = toClient.toByteArray()
        val cr2 = FrameReader(ByteArrayInputStream(all))
        check(cr2.readHeader(h))
        cr2.readPayload(h)
        check(cr2.readHeader(h))
        assertEquals(MessageType.AUTH_PROOF, h.type)
        cr2.readPayload(h)
        c.onServerProof(cr2.payload, 0, h.length)
        assertEquals(HandshakeState.ESTABLISHED, c.state)
        assertEquals(HandshakeState.ESTABLISHED, s.state)

        // Switch to the session phase and exchange traffic.
        cw.phase = Phase.SESSION
        sw.phase = Phase.SESSION
        cr2.phase = Phase.SESSION
        cw.touch(0, TouchAction.DOWN, 0.5f, 0.5f)
        sw.writeMessage(VideoSize(540, 1200, DisplayGeometry(1080, 2400, 0)))
    }

    @Test
    fun wrongSecretIsRejectedByTheServer() {
        val c = client(secret = ByteArray(32) { 7 })
        val s = server()
        val hello = c.hello().encode()
        val sh = s.onHello(hello, 0, hello.size).encode()
        val proof = c.onServerHello(sh, 0, sh.size).encode()
        expect(ErrorCode.AUTH_FAILED) { s.onClientProof(proof, 0, proof.size) }
        assertEquals(HandshakeState.FAILED, s.state)
        expect(ErrorCode.UNEXPECTED_MESSAGE) { s.onClientProof(proof, 0, proof.size) }
    }

    @Test
    fun serverWithWrongSecretIsRejectedByTheClient() {
        val c = client()
        val s = server(secret = ByteArray(32) { 9 })
        val hello = c.hello().encode()
        val sh = s.onHello(hello, 0, hello.size).encode()
        val proof = c.onServerHello(sh, 0, sh.size).encode()
        // A rogue server just answers with a bogus proof.
        val bogus = AuthProof(ByteArray(32)).encode()
        expect(ErrorCode.AUTH_FAILED) { c.onServerProof(bogus, 0, bogus.size) }
        assertEquals(HandshakeState.FAILED, c.state)
        expect(ErrorCode.AUTH_FAILED) { s.onClientProof(proof, 0, proof.size) }
    }

    @Test
    fun tamperedServerHelloChangesTheTranscriptAndFails() {
        val c = client()
        val s = server()
        val hello = c.hello().encode()
        val sh = s.onHello(hello, 0, hello.size).encode()
        // flip a capabilities bit
        val tampered = sh.copyOf().also { it[5] = (it[5].toInt() xor 0x10).toByte() }
        val proof = c.onServerHello(tampered, 0, tampered.size).encode()
        expect(ErrorCode.AUTH_FAILED) { s.onClientProof(proof, 0, proof.size) }
    }

    @Test
    fun tamperedHelloChangesTheTranscriptAndFails() {
        val c = client()
        val s = server()
        val hello = c.hello().encode()
        // flip a features bit
        val tampered = hello.copyOf().also { it[9] = (it[9].toInt() xor 0x04).toByte() }
        val sh = s.onHello(tampered, 0, tampered.size).encode()
        val proof = c.onServerHello(sh, 0, sh.size).encode()
        expect(ErrorCode.AUTH_FAILED) { s.onClientProof(proof, 0, proof.size) }
    }

    @Test
    fun clientProofCannotBeReflectedAsServerProof() {
        val c = client()
        val s = server()
        val hello = c.hello().encode()
        val sh = s.onHello(hello, 0, hello.size).encode()
        val proof = c.onServerHello(sh, 0, sh.size).encode()
        expect(ErrorCode.AUTH_FAILED) { c.onServerProof(proof, 0, proof.size) }
    }

    @Test
    fun versionNegotiation() {
        val s = server()
        val old = Hello(1, 0, clientNonce).encode()
        expect(ErrorCode.VERSION_MISMATCH) { s.onHello(old, 0, old.size) }

        val newer = Hello(7, 0xFF, clientNonce).encode()
        val s2 = server()
        assertEquals(2, s2.onHello(newer, 0, newer.size).version)
        assertEquals(0x0F, s2.negotiatedFeatures)

        // A server that answers with a version the client did not offer is rejected.
        val c = client()
        c.hello()
        val bad = ServerHello(3, 0, serverNonce, device).encode()
        expect(ErrorCode.VERSION_MISMATCH) { c.onServerHello(bad, 0, bad.size) }
        val c2 = client()
        c2.hello()
        val zero = ServerHello(1, 0, serverNonce, device).encode()
        expect(ErrorCode.VERSION_MISMATCH) { c2.onServerHello(zero, 0, zero.size) }
    }

    @Test
    fun badMagicAndBadLengthsAreRejected() {
        val s = server()
        val hello = Hello(2, 0, clientNonce).encode().also { it[3] = '3'.code.toByte() }
        expect(ErrorCode.BAD_MAGIC) { s.onHello(hello, 0, hello.size) }
        expect(ErrorCode.MALFORMED_MESSAGE) { server().onHello(ByteArray(3), 0, 3) }
    }

    @Test
    fun messagesOutOfOrderAreRejected() {
        val s = server()
        val proof = AuthProof(ByteArray(32)).encode()
        expect(ErrorCode.UNEXPECTED_MESSAGE) { s.onClientProof(proof, 0, proof.size) }
        assertEquals(HandshakeState.FAILED, s.state)

        val c = client()
        val sh = ServerHello(2, 0, serverNonce, device).encode()
        expect(ErrorCode.UNEXPECTED_MESSAGE) { c.onServerHello(sh, 0, sh.size) }

        val c2 = client()
        c2.hello()
        expect(ErrorCode.UNEXPECTED_MESSAGE) { c2.hello() }
        expect(ErrorCode.UNEXPECTED_MESSAGE) { c2.onServerProof(proof, 0, proof.size) }
    }

    @Test
    fun noSessionMessageIsAcceptedBeforeAuthentication() {
        val touch = VectorSupport.control(0x20, param = 0, x = 0.5f, y = 0.5f)
        val reader = ControlReader(ByteArrayInputStream(touch))
        expect(ErrorCode.UNEXPECTED_MESSAGE) { reader.read(ControlPacket()) }
    }
}
