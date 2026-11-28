package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.protocol.JvmAesGcmEnvelopeCipher
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbKeyStoreAndSpake2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `KeystoreProtectedAdbKeyStore persists RSA-2048 identity and detects tampering`() {
        val masterKey = ByteArray(32) { (it + 7).toByte() }
        val dir = tempFolder.newFolder("adb_keys").toPath()
        val store1 = KeystoreProtectedAdbKeyStore(dir, JvmAesGcmEnvelopeCipher(masterKey))

        val created = store1.getOrCreateIdentity("viewer_primary")
        assertTrue(store1.hasIdentity("viewer_primary"))
        assertEquals(setOf("viewer_primary"), store1.listAliases())
        created.certificate.verify(created.publicKey)

        // Reload from disk in a brand-new instance with the same master key.
        val store2 = KeystoreProtectedAdbKeyStore(dir, JvmAesGcmEnvelopeCipher(masterKey))
        val reloaded = store2.loadIdentity("viewer_primary")
        assertNotNull(reloaded)
        assertEquals(created.sha256FingerprintHex, reloaded!!.sha256FingerprintHex)
        assertArrayEquals(created.encodedAuthorizedKey(), reloaded.encodedAuthorizedKey())

        // Verify Android 524-byte RSA public key binary struct round-trips cleanly.
        val binaryPub = AndroidPubkeyCodec.encodeBinaryStruct(created.publicKey)
        assertEquals(AndroidPubkeyCodec.ANDROID_PUBKEY_STRUCT_BYTES, binaryPub.size)
        val decodedPub = AndroidPubkeyCodec.decodeBinaryStruct(binaryPub)
        assertEquals(created.publicKey.modulus, decodedPub.modulus)
        assertEquals(created.publicKey.publicExponent, decodedPub.publicExponent)

        // Cross-alias file swap must fail AEAD AAD authentication.
        store2.getOrCreateIdentity("viewer_secondary")
        val primaryFile = dir.resolve("viewer_primary.adbkey")
        val secondaryFile = dir.resolve("viewer_secondary.adbkey")
        Files.copy(secondaryFile, primaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        try {
            store2.loadIdentity("viewer_primary")
            fail("Expected KeyStoreSecurityException on swapped alias file")
        } catch (_: KeyStoreSecurityException) {
            // Expected.
        }
    }

    @Test
    fun `Spake25519Context derives matching keys and rejects bit corruptions`() {
        val aliceName = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        val bobName = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
        val password = "482915-exported-tls-material".toByteArray(Charsets.UTF_8)
        val random = SecureRandom()

        repeat(10) {
            Spake25519Context(Spake2Role.ALICE, aliceName, bobName, random).use { alice ->
                Spake25519Context(Spake2Role.BOB, bobName, aliceName, random).use { bob ->
                    val aliceMsg = alice.generateMessage(password)
                    val bobMsg = bob.generateMessage(password)
                    val aliceKey = alice.processMessage(bobMsg)
                    val bobKey = bob.processMessage(aliceMsg)
                    assertNotNull(aliceKey)
                    assertNotNull(bobKey)
                    assertArrayEquals(aliceKey, bobKey)
                }
            }
        }

        // Wrong password must never match.
        Spake25519Context(Spake2Role.ALICE, aliceName, bobName, random).use { alice ->
            Spake25519Context(Spake2Role.BOB, bobName, aliceName, random).use { bob ->
                val aliceMsg = alice.generateMessage(password)
                val bobMsg = bob.generateMessage("999999-exported-tls-material".toByteArray())
                val aliceKey = alice.processMessage(bobMsg)!!
                val bobKey = bob.processMessage(aliceMsg)!!
                assertFalse(aliceKey.contentEquals(bobKey))
            }
        }

        // Corrupting every single bit (0..255) of Alice's 32-byte message must fail or mismatch.
        for (bit in 0 until 256) {
            Spake25519Context(Spake2Role.ALICE, aliceName, bobName, random).use { alice ->
                Spake25519Context(Spake2Role.BOB, bobName, aliceName, random).use { bob ->
                    val aliceMsg = alice.generateMessage(password)
                    val bobMsg = bob.generateMessage(password)
                    aliceMsg[bit / 8] = (aliceMsg[bit / 8].toInt() xor (1 shl (bit and 7))).toByte()
                    val aliceKey = alice.processMessage(bobMsg)
                    val bobKey = bob.processMessage(aliceMsg)
                    val matched =
                        aliceKey != null && bobKey != null && aliceKey.contentEquals(bobKey)
                    assertFalse("Bit $bit corruption unexpectedly produced matching keys", matched)
                }
            }
        }
    }
}
