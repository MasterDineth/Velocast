package com.mastersoft.velocast.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mastersoft.velocast.protocol.EncryptedFileKeyStore
import com.mastersoft.velocast.protocol.EnvelopeCipher
import com.mastersoft.velocast.protocol.KeyStore
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import com.mastersoft.velocast.protocol.ProtocolV2
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore as JcaKeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware/TEE-backed [EnvelopeCipher] storing a non-exportable 256-bit `AES/GCM/NoPadding` master
 * key inside Android's `AndroidKeyStore`.
 */
class AndroidKeyStoreEnvelopeCipher(private val masterKeyAlias: String = DEFAULT_MASTER_KEY_ALIAS) :
    EnvelopeCipher {
    private val keyLock = Any()

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val sealed = cipher.doFinal(plaintext)
        val iv = cipher.iv
            ?: throw KeyStoreSecurityException("AndroidKeyStore cipher did not provide an IV")
        if (iv.size != GCM_IV_BYTES) {
            throw KeyStoreSecurityException("Unexpected AndroidKeyStore GCM IV size: ${iv.size}")
        }
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + iv.size)
        return out
    }

    override fun decrypt(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val iv = extractEnvelopeIv(ciphertext)
        val secretKey = loadExistingMasterKey()
            ?: throw KeyStoreSecurityException(
                "Missing AndroidKeyStore master key '$masterKeyAlias'"
            )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext, 1 + iv.size, ciphertext.size - (1 + iv.size))
    }

    private fun extractEnvelopeIv(ciphertext: ByteArray): ByteArray {
        val valid = ciphertext.isNotEmpty() &&
            (ciphertext[0].toInt() and BYTE_MASK) == GCM_IV_BYTES &&
            ciphertext.size >= 1 + GCM_IV_BYTES + ProtocolV2.AEAD_TAG_SIZE
        if (!valid) {
            throw KeyStoreSecurityException("Malformed AndroidKeyStore IV or truncated ciphertext")
        }
        return ciphertext.copyOfRange(1, 1 + GCM_IV_BYTES)
    }

    private fun getOrCreateMasterKey(): SecretKey = synchronized(keyLock) {
        loadExistingMasterKey() ?: generateMasterKey()
    }

    private fun loadExistingMasterKey(): SecretKey? {
        val ks = JcaKeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(masterKeyAlias, null) as? JcaKeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    private fun generateMasterKey(): SecretKey {
        try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                masterKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            return generator.generateKey()
        } catch (e: GeneralSecurityException) {
            throw KeyStoreSecurityException(
                "Failed generating AndroidKeyStore master key '$masterKeyAlias'",
                e
            )
        }
    }

    override fun toString(): String = "AndroidKeyStoreEnvelopeCipher(alias=$masterKeyAlias)"

    companion object {
        const val DEFAULT_MASTER_KEY_ALIAS = "com.mastersoft.velocast.viewer.master_key.v1"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val BYTE_MASK = 0xFF
    }
}

/**
 * Viewer [KeyStore] persisting encrypted `PAIRING_SECRET` files exclusively under
 * [Context.getNoBackupFilesDir] protected by [AndroidKeyStoreEnvelopeCipher].
 */
class AndroidKeyStoreSecretStore(
    directory: Path,
    envelopeCipher: EnvelopeCipher = AndroidKeyStoreEnvelopeCipher()
) : KeyStore by EncryptedFileKeyStore(directory, envelopeCipher) {

    constructor(
        context: Context,
        subDirectory: String = DEFAULT_SUBDIRECTORY,
        masterKeyAlias: String = AndroidKeyStoreEnvelopeCipher.DEFAULT_MASTER_KEY_ALIAS
    ) : this(
        directory = context.applicationContext.noBackupFilesDir.toPath().resolve(subDirectory),
        envelopeCipher = AndroidKeyStoreEnvelopeCipher(masterKeyAlias)
    )

    companion object {
        const val DEFAULT_SUBDIRECTORY = "velocast_secrets"
        const val DEFAULT_PAIRING_ALIAS = "default_pairing_secret"
    }
}
