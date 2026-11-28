package com.mastersoft.velocast.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RFC 5869 official HKDF-SHA256 test vectors, AES-256-GCM known-answer tests (KATs),
 * directional nonce construction, and [HkdfAuthenticator] handshake verification tests.
 */
class SecurityCryptoTest {
    private fun hex(s: String): ByteArray = VectorSupport.hexToBytes(s)

    private fun expectProtocolError(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("Expected ProtocolException($code)")
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun hkdfSha256PassesRfc5869TestCase1() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expectedPrk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val expectedOkm = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )

        val prk = HkdfSha256.extract(salt, ikm)
        assertArrayEquals(expectedPrk, prk)
        val okm = HkdfSha256.expand(prk, info, 42)
        assertArrayEquals(expectedOkm, okm)
        assertArrayEquals(expectedOkm, HkdfSha256.deriveKey(ikm, salt, info, 42))
    }

    @Test
    fun hkdfSha256PassesRfc5869TestCase2LongInputsAndOutputs() {
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f"
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        )
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        )
        val expectedPrk = hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244")
        val expectedOkm = hex(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87"
        )

        val prk = HkdfSha256.extract(salt, ikm)
        assertArrayEquals(expectedPrk, prk)
        assertArrayEquals(expectedOkm, HkdfSha256.expand(prk, info, 82))
    }

    @Test
    fun hkdfSha256PassesRfc5869TestCase3EmptySaltAndInfo() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val expectedPrk = hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04")
        val expectedOkm = hex(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8"
        )

        val prkNullSalt = HkdfSha256.extract(null, ikm)
        val prkEmptySalt = HkdfSha256.extract(ByteArray(0), ikm)
        assertArrayEquals(expectedPrk, prkNullSalt)
        assertArrayEquals(expectedPrk, prkEmptySalt)
        assertArrayEquals(expectedOkm, HkdfSha256.expand(prkEmptySalt, ByteArray(0), 42))
    }

    @Test
    fun aes256GcmSessionCipherMatchesKnownAnswerTestAndDirectionalNonces() {
        // Directional nonce layout: 4-byte prefix ("C2S\0" = 43325300, "S2C\0" = 53324300) + u64be
        assertArrayEquals(
            hex("433253000000000000000000"),
            CipherDirection.CLIENT_TO_SERVER.buildNonce(0L)
        )
        assertArrayEquals(
            hex("533243000000000000000005"),
            CipherDirection.SERVER_TO_CLIENT.buildNonce(5L)
        )

        // Independent OpenSSL (node:crypto) AES-256-GCM KATs for both directions (counter = 0):
        val key = ByteArray(32) { it.toByte() }
        val c2sAad = hex("01000000000000000000000000000010")
        val c2sPlain = hex("20000001000000003f0000003e800000")
        val expectedC2s = hex(
            "f247570874eb518a8eb0bdb9b66ae517" +
                "cb54cb805931ba6ffa46642cc43c9746"
        )
        val s2cAad = hex("0100000000000000000000000000000c")
        val s2cPlain = hex("0102030400000000000f4240")
        val expectedS2c = hex(
            "657ef6517795b4d92c84d93a" +
                "67fdc7647dd77693355e8f1cf9ca4b69"
        )

        val c2sSender = AesGcmSessionCipher(key, CipherDirection.CLIENT_TO_SERVER)
        val c2sReceiver = AesGcmSessionCipher(key, CipherDirection.CLIENT_TO_SERVER)
        val s2cSender = AesGcmSessionCipher(key, CipherDirection.SERVER_TO_CLIENT)
        val s2cReceiver = AesGcmSessionCipher(key, CipherDirection.SERVER_TO_CLIENT)
        try {
            val sealedC2s = c2sSender.sealWithCounter(0L, c2sAad, c2sPlain)
            assertArrayEquals(expectedC2s, sealedC2s)
            assertEquals(1L, c2sSender.nextCounter)
            assertArrayEquals(c2sPlain, c2sReceiver.open(0, 0L, c2sAad, sealedC2s))

            val sealedS2c = s2cSender.sealWithCounter(0L, s2cAad, s2cPlain)
            assertArrayEquals(expectedS2c, sealedS2c)
            assertArrayEquals(s2cPlain, s2cReceiver.open(0, 0L, s2cAad, sealedS2c))
        } finally {
            c2sSender.close()
            c2sReceiver.close()
            s2cSender.close()
            s2cReceiver.close()
        }
    }

    @Test
    fun hkdfHandshakeDerivesMatchingDirectionalKeysAndRejectsDowngradeOrWrongSecret() {
        val secret = ByteArray(32) { (0x21 + it).toByte() }
        val clientNonce = ByteArray(16) { (0x10 + it).toByte() }
        val serverNonce = ByteArray(16) { (0x80 + it).toByte() }

        val clientAuth = HkdfAuthenticator(secret, AuthMode.SESSION_SECRET)
        val serverAuth = HkdfAuthenticator(secret, AuthMode.PAIRING_SECRET)

        val client = ClientHandshake(
            clientAuth,
            Features.CLIPBOARD or Features.SECURE_CHANNEL,
            clientNonce
        )
        val server = ServerHandshake(
            serverAuth,
            Features.CLIPBOARD or Features.DISPLAY_POWER,
            serverNonce
        )

        val hello = client.hello().encode()
        val serverHello = server.onHello(hello, 0, hello.size).encode()
        val clientProof = client.onServerHello(serverHello, 0, serverHello.size).encode()
        val serverProof = server.onClientProof(clientProof, 0, clientProof.size).encode()
        client.onServerProof(serverProof, 0, serverProof.size)

        assertEquals(HandshakeState.ESTABLISHED, client.state)
        assertEquals(HandshakeState.ESTABLISHED, server.state)
        assertTrue((client.negotiatedFeatures and Features.SECURE_CHANNEL) != 0)

        val clientKeys = client.deriveSessionKeys()
        val serverKeys = server.deriveSessionKeys()
        assertNotNull(clientKeys)
        assertNotNull(serverKeys)
        assertArrayEquals(
            clientKeys!!.copyClientToServerKey(),
            serverKeys!!.copyClientToServerKey()
        )
        assertArrayEquals(clientKeys.copyServerToClientKey(), serverKeys.copyServerToClientKey())
        assertFalse(
            clientKeys.copyClientToServerKey().contentEquals(clientKeys.copyServerToClientKey())
        )
        clientKeys.close()
        serverKeys.close()

        // Plaintext downgrade attempt (client omits SECURE_CHANNEL) -> rejected with AUTH_FAILED
        val downgradeClient = ClientHandshake(clientAuth, Features.CLIPBOARD, clientNonce)
        val downgradeServer = ServerHandshake(serverAuth, Features.CLIPBOARD, serverNonce)
        val noSecHello = downgradeClient.hello().encode()
        expectProtocolError(ErrorCode.AUTH_FAILED) {
            downgradeServer.onHello(noSecHello, 0, noSecHello.size)
        }

        // Wrong secret -> rejected with AUTH_FAILED
        val wrongClient = ClientHandshake(
            HkdfAuthenticator(ByteArray(32) { 0x77 }),
            Features.SECURE_CHANNEL,
            clientNonce
        )
        val s2 = ServerHandshake(serverAuth, Features.SECURE_CHANNEL, serverNonce)
        val h2 = wrongClient.hello().encode()
        val sh2 = s2.onHello(h2, 0, h2.size).encode()
        val badProof = wrongClient.onServerHello(sh2, 0, sh2.size).encode()
        expectProtocolError(ErrorCode.AUTH_FAILED) {
            s2.onClientProof(badProof, 0, badProof.size)
        }

        clientAuth.close()
        serverAuth.close()
    }
}
