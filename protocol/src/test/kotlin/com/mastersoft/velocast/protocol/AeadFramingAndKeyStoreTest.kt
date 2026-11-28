package com.mastersoft.velocast.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Negative AEAD framing tests (wrong secret, replay, truncation, reordering, tampered header,
 * nonce reuse), automatic rekeying before `2^32` frames, [EncryptedFileKeyStore] tests, and
 * [SecretRedactor] tests.
 */
class AeadFramingAndKeyStoreTest {
    private val keyA = ByteArray(32) { (it + 1).toByte() }
    private val keyB = ByteArray(32) { (it + 0x41).toByte() }

    private fun expectError(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("Expected ProtocolException($code)")
        } catch (e: ProtocolException) {
            assertEquals(code, e.code)
        }
    }

    private fun recordTwoPongFrames(): Pair<ByteArray, ByteArray> {
        val out = ByteArrayOutputStream()
        val sender = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
        val fw = FrameWriter(out).apply {
            cipher = sender
            phase = Phase.SESSION
        }
        fw.writeMessage(Pong(token = 10, serverUs = 1_000L))
        val firstEnd = out.size()
        fw.writeMessage(Pong(token = 20, serverUs = 2_000L))
        val all = out.toByteArray()
        sender.close()
        return all.copyOfRange(0, firstEnd) to all.copyOfRange(firstEnd, all.size)
    }

    @Test
    fun rejectsWrongKeyReplayedAndReorderedFrames() {
        val (frame0, frame1) = recordTwoPongFrames()
        val h = FrameHeader()

        // 1. Wrong secret / wrong key -> AUTH_FAILED
        val wrongReader = FrameReader(ByteArrayInputStream(frame0)).apply {
            cipher = AesGcmSessionCipher(keyB, CipherDirection.SERVER_TO_CLIENT)
            phase = Phase.SESSION
        }
        expectError(ErrorCode.AUTH_FAILED) { wrongReader.readHeader(h) }

        // 2. Replayed frame ([frame0, frame0]) -> second fails with AUTH_FAILED
        val replayReader = FrameReader(ByteArrayInputStream(frame0 + frame0)).apply {
            cipher = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
            phase = Phase.SESSION
        }
        assertTrue(replayReader.readHeader(h))
        replayReader.readPayload(h)
        assertEquals(10, Pong.decode(replayReader.payload, 0, h.length).token)
        expectError(ErrorCode.AUTH_FAILED) { replayReader.readHeader(h) }

        // Replay with forged header counter=1 -> AAD/nonce mismatch -> AUTH_FAILED
        val forgedReplay = frame0.copyOf().also { BigEndian.putI64(it, 4, 1L) }
        val forgedReader = FrameReader(ByteArrayInputStream(frame0 + forgedReplay)).apply {
            cipher = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
            phase = Phase.SESSION
        }
        assertTrue(forgedReader.readHeader(h))
        forgedReader.readPayload(h)
        expectError(ErrorCode.AUTH_FAILED) { forgedReader.readHeader(h) }

        // 3. Reordered frames ([frame1, frame0]) -> strict monotonicity rejects frame1 (1 > 0)
        val reorderedReader = FrameReader(ByteArrayInputStream(frame1 + frame0)).apply {
            cipher = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
            phase = Phase.SESSION
        }
        expectError(ErrorCode.AUTH_FAILED) { reorderedReader.readHeader(h) }
    }

    @Test
    fun rejectsTamperedHeaderTruncatedFramesAndTamperedControlCiphertext() {
        val (frame0, _) = recordTwoPongFrames()
        val h = FrameHeader()

        // 4. Tampered header (flip recordType, flags, and epoch bytes) -> AUTH_FAILED
        for (headerIdx in listOf(0, 1, 3)) {
            val tampered = frame0.copyOf().also {
                it[headerIdx] = (it[headerIdx].toInt() xor 0x01).toByte()
            }
            val tamperedReader = FrameReader(ByteArrayInputStream(tampered)).apply {
                cipher = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
                phase = Phase.SESSION
            }
            expectError(ErrorCode.AUTH_FAILED) { tamperedReader.readHeader(h) }
        }

        // 5. Truncated frame (mid-header, mid-ciphertext, 1 byte short of GCM tag) -> TRUNCATED
        for (cutLen in listOf(5, ProtocolV2.AEAD_HEADER_SIZE + 4, frame0.size - 1)) {
            val truncReader = FrameReader(ByteArrayInputStream(frame0.copyOf(cutLen))).apply {
                cipher = AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT)
                phase = Phase.SESSION
            }
            expectError(ErrorCode.TRUNCATED) { truncReader.readHeader(h) }
        }

        // C->S ControlReader rejects tampered GCM tag
        val c2sOut = ByteArrayOutputStream()
        ControlWriter(c2sOut).apply {
            cipher = AesGcmSessionCipher(keyA, CipherDirection.CLIENT_TO_SERVER)
            phase = Phase.SESSION
            touch(0, TouchAction.DOWN, 0.5f, 0.25f)
            cipher?.close()
        }
        val c2sWire = c2sOut.toByteArray()
        c2sWire[c2sWire.lastIndex] = (c2sWire.last().toInt() xor 0x80).toByte()
        val cr = ControlReader(ByteArrayInputStream(c2sWire)).apply {
            cipher = AesGcmSessionCipher(keyA, CipherDirection.CLIENT_TO_SERVER)
            phase = Phase.SESSION
        }
        expectError(ErrorCode.AUTH_FAILED) { cr.read(ControlPacket()) }
    }

    @Test
    fun rejectsNonceReuseAndSkippedCountersAndEnforcesRekeyBeforeTwoToThe32() {
        val cipher =
            AesGcmSessionCipher(keyA, CipherDirection.CLIENT_TO_SERVER, rekeyAfterFrames = 3L)
        val aad = ByteArray(ProtocolV2.AEAD_HEADER_SIZE)
        val plain = byteArrayOf(1, 2, 3, 4)

        cipher.sealWithCounter(0L, aad, plain)
        assertEquals(1L, cipher.nextCounter)
        expectError(ErrorCode.AUTH_FAILED) { cipher.sealWithCounter(0L, aad, plain) }
        expectError(ErrorCode.AUTH_FAILED) { cipher.sealWithCounter(2L, aad, plain) }
        cipher.close()

        val wire = ByteArrayOutputStream()
        val senderCipher =
            AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT, rekeyAfterFrames = 3L)
        val receiverCipher =
            AesGcmSessionCipher(keyA, CipherDirection.SERVER_TO_CLIENT, rekeyAfterFrames = 3L)
        val writer = FrameWriter(wire).apply {
            this.cipher = senderCipher
            this.phase = Phase.SESSION
        }
        for (token in 1..10) {
            writer.writeMessage(Pong(token = token, serverUs = token * 100L))
        }
        assertEquals(3, senderCipher.currentEpoch)
        assertEquals(1L, senderCipher.nextCounter)

        val reader = FrameReader(ByteArrayInputStream(wire.toByteArray())).apply {
            this.cipher = receiverCipher
            this.phase = Phase.SESSION
        }
        val h = FrameHeader()
        for (expectedToken in 1..10) {
            assertTrue(reader.readHeader(h))
            reader.readPayload(h)
            assertEquals(expectedToken, Pong.decode(reader.payload, 0, h.length).token)
        }
        assertFalse(reader.readHeader(h))
        assertEquals(3, receiverCipher.currentEpoch)
        senderCipher.close()
        receiverCipher.close()
    }

    @Test
    fun encryptedFileKeyStoreEncryptsAtRestAndDetectsTamperingOrAliasSwaps() {
        val dir = Files.createTempDirectory("velocast-keystore-test")
        try {
            val store = EncryptedFileKeyStore(dir, JvmAesGcmEnvelopeCipher(keyA))
            val secretPhone1 = SessionSecrets.generate()
            val secretPhone2 = SessionSecrets.generate()

            store.putSecret("old-pixel-6", secretPhone1)
            store.putSecret("old-xiaomi", secretPhone2)

            assertTrue(store.hasSecret("old-pixel-6"))
            assertEquals(setOf("old-pixel-6", "old-xiaomi"), store.listAliases())
            assertArrayEquals(secretPhone1, store.getSecret("old-pixel-6"))
            assertArrayEquals(secretPhone2, store.getSecret("old-xiaomi"))
            assertNull(store.getSecret("nonexistent"))

            verifyKeyStoreTamperAndTraversalRejected(store, dir, secretPhone1)

            assertTrue(store.deleteSecret("old-xiaomi"))
            assertFalse(store.hasSecret("old-xiaomi"))
            store.clear()
            assertTrue(store.listAliases().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun verifyKeyStoreTamperAndTraversalRejected(
        store: EncryptedFileKeyStore,
        dir: Path,
        secretPhone1: ByteArray
    ) {
        val file1 = dir.resolve("old-pixel-6.vck")
        val file2 = dir.resolve("old-xiaomi.vck")
        val raw1 = Files.readAllBytes(file1)
        assertFalse(containsSubarray(raw1, secretPhone1))

        Files.write(file1, Files.readAllBytes(file2))
        try {
            store.getSecret("old-pixel-6")
            fail("Expected KeyStoreSecurityException on swapped alias file")
        } catch (_: KeyStoreSecurityException) {
            // expected
        }

        Files.write(file1, raw1.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        try {
            store.getSecret("old-pixel-6")
            fail("Expected KeyStoreSecurityException on tampered ciphertext")
        } catch (_: KeyStoreSecurityException) {
            // expected
        }

        try {
            store.putSecret("../escape", secretPhone1)
            fail("Expected IllegalArgumentException on path traversal alias")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun secretRedactorScrubsRegisteredSecretsLabelsAndLongHexWhilePreservingSafeText() {
        val redactor = SecretRedactor()
        val rawSecret = ByteArray(32) { (0x10 + it).toByte() }
        redactor.registerSecret(rawSecret)
        val b64 = java.util.Base64.getEncoder().encodeToString(rawSecret)
        val hex64 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        val dirty = "Failed auth: secret=mySuperSecret123 pin: 984210 " +
            "rawHex=$hex64 b64=$b64 port=27183"
        val clean = redactor.redact(dirty)

        assertFalse(clean.contains("mySuperSecret123"))
        assertFalse(clean.contains("984210"))
        assertFalse(clean.contains(hex64))
        assertFalse(clean.contains(b64))
        assertTrue(clean.contains("secret=<redacted>"))
        assertTrue(clean.contains("pin: <redacted>"))
        assertTrue(clean.contains("port=27183"))

        val ex = IllegalStateException("pairing_secret=$hex64 leaked in exception")
        val redactedTrace = redactor.redact(ex)
        assertFalse(redactedTrace.contains(hex64))
        assertTrue(redactedTrace.contains("pairing_secret=<redacted>"))
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() &&
            haystack.size >= needle.size &&
            (0..(haystack.size - needle.size)).any { start ->
                needle.indices.all { j -> haystack[start + j] == needle[j] }
            }
}
