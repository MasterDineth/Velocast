package com.mastersoft.velocast.protocol

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raised when an encrypted [KeyStore] entry fails authentication, integrity, or key access. */
class KeyStoreSecurityException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * Persistent encrypted store for secret byte arrays (such as 256-bit `PAIRING_SECRET`s).
 *
 * On Android, implementations wrap an `AES-256-GCM` master key held inside `AndroidKeyStore` and
 * persist ciphertexts exclusively under `Context.noBackupFilesDir`.
 */
interface KeyStore {
    fun putSecret(alias: String, secret: ByteArray)

    fun getSecret(alias: String): ByteArray?

    fun hasSecret(alias: String): Boolean

    fun deleteSecret(alias: String): Boolean

    fun listAliases(): Set<String>

    fun clear()
}

/**
 * Master AEAD primitive used by [EncryptedFileKeyStore] to seal and unseal individual secret files.
 */
interface EnvelopeCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(aad: ByteArray, plaintext: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(aad: ByteArray, ciphertext: ByteArray): ByteArray
}

/**
 * Pure-JVM `AES-256-GCM` [EnvelopeCipher] using a fresh 96-bit CSPRNG IV per encryption.
 * Wire layout: `ivLength (1B = 12) || iv (12B) || ciphertextWithTag`.
 */
class JvmAesGcmEnvelopeCipher(
    masterKey: ByteArray,
    private val random: SecureRandom = SecureRandom()
) : EnvelopeCipher {
    private val keySpec: SecretKeySpec

    init {
        require(masterKey.size == ProtocolV2.SECRET_SIZE) {
            "Master key must be ${ProtocolV2.SECRET_SIZE} bytes"
        }
        val copy = masterKey.copyOf()
        try {
            keySpec = SecretKeySpec(copy, KEY_ALGORITHM)
        } finally {
            copy.fill(0)
        }
    }

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val sealed = cipher.doFinal(plaintext)
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + iv.size)
        return out
    }

    override fun decrypt(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.isEmpty()) {
            throw KeyStoreSecurityException("Empty envelope ciphertext")
        }
        val ivLen = ciphertext[0].toInt() and BYTE_MASK
        val minSize = 1 + ivLen + ProtocolV2.AEAD_TAG_SIZE
        if (ivLen != GCM_IV_BYTES || ciphertext.size < minSize) {
            throw KeyStoreSecurityException("Malformed envelope IV or truncated ciphertext")
        }
        val iv = ciphertext.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext, 1 + ivLen, ciphertext.size - (1 + ivLen))
    }

    override fun toString(): String = "JvmAesGcmEnvelopeCipher(<redacted>)"

    private companion object {
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val BYTE_MASK = 0xFF
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** In-memory [KeyStore] implementation for fast unit testing. */
class InMemoryKeyStore : KeyStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, ByteArray>()

    override fun putSecret(alias: String, secret: ByteArray) {
        KeyStoreAliasRules.validateAlias(alias)
        KeyStoreAliasRules.validateSecret(secret)
        synchronized(lock) {
            entries.put(alias, secret.copyOf())?.fill(0)
        }
    }

    override fun getSecret(alias: String): ByteArray? {
        KeyStoreAliasRules.validateAlias(alias)
        return synchronized(lock) { entries[alias]?.copyOf() }
    }

    override fun hasSecret(alias: String): Boolean {
        KeyStoreAliasRules.validateAlias(alias)
        return synchronized(lock) { entries.containsKey(alias) }
    }

    override fun deleteSecret(alias: String): Boolean {
        KeyStoreAliasRules.validateAlias(alias)
        return synchronized(lock) {
            val old = entries.remove(alias)
            old?.fill(0)
            old != null
        }
    }

    override fun listAliases(): Set<String> =
        synchronized(lock) { entries.keys.sorted().toCollection(LinkedHashSet()) }

    override fun clear() {
        synchronized(lock) {
            for (value in entries.values) value.fill(0)
            entries.clear()
        }
    }
}

/**
 * File-backed [KeyStore] storing one AEAD-encrypted `<alias>.vck` file per secret inside
 * [directory], binding `"VLCK" || version || alias` into the AES-GCM associated data so files
 * cannot be forged or swapped across aliases.
 */
class EncryptedFileKeyStore(
    private val directory: Path,
    private val envelopeCipher: EnvelopeCipher
) : KeyStore {
    private val lock = Any()

    override fun putSecret(alias: String, secret: ByteArray) {
        KeyStoreAliasRules.validateAlias(alias)
        KeyStoreAliasRules.validateSecret(secret)
        val aad = KeyStoreAliasRules.buildFileHeaderAad(alias)
        val plainCopy = secret.copyOf()
        val encrypted = try {
            envelopeCipher.encrypt(aad, plainCopy)
        } catch (e: GeneralSecurityException) {
            throw KeyStoreSecurityException("Failed encrypting secret for alias '$alias'", e)
        } finally {
            plainCopy.fill(0)
        }
        val fileBytes = ByteArray(aad.size + encrypted.size)
        aad.copyInto(fileBytes, 0)
        encrypted.copyInto(fileBytes, aad.size)
        synchronized(lock) {
            writeAtomically(entryPath(alias), fileBytes)
        }
    }

    override fun getSecret(alias: String): ByteArray? {
        KeyStoreAliasRules.validateAlias(alias)
        val raw = synchronized(lock) {
            val target = entryPath(alias)
            if (!Files.isRegularFile(target)) null else Files.readAllBytes(target)
        } ?: return null

        val expectedAad = KeyStoreAliasRules.buildFileHeaderAad(alias)
        if (raw.size <= expectedAad.size ||
            !ConstantTime.equals(expectedAad, raw.copyOfRange(0, expectedAad.size))
        ) {
            throw KeyStoreSecurityException("Tampered or mismatched header for alias '$alias'")
        }
        val cipherSlice = raw.copyOfRange(expectedAad.size, raw.size)
        return try {
            envelopeCipher.decrypt(expectedAad, cipherSlice)
        } catch (e: GeneralSecurityException) {
            throw KeyStoreSecurityException("Failed authenticating secret for alias '$alias'", e)
        }
    }

    override fun hasSecret(alias: String): Boolean {
        KeyStoreAliasRules.validateAlias(alias)
        return synchronized(lock) { Files.isRegularFile(entryPath(alias)) }
    }

    override fun deleteSecret(alias: String): Boolean {
        KeyStoreAliasRules.validateAlias(alias)
        return synchronized(lock) { Files.deleteIfExists(entryPath(alias)) }
    }

    override fun listAliases(): Set<String> = synchronized(lock) {
        if (!Files.isDirectory(directory)) return emptySet()
        val result = ArrayList<String>()
        Files.newDirectoryStream(directory, "*$FILE_SUFFIX").use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                val alias = name.removeSuffix(FILE_SUFFIX)
                if (KeyStoreAliasRules.isValidAlias(alias)) {
                    result.add(alias)
                }
            }
        }
        result.sorted().toCollection(LinkedHashSet())
    }

    override fun clear() {
        synchronized(lock) {
            if (!Files.isDirectory(directory)) return
            Files.newDirectoryStream(directory).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (name.endsWith(FILE_SUFFIX) || name.endsWith(TEMP_SUFFIX)) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }

    private fun entryPath(alias: String): Path = directory.resolve("$alias$FILE_SUFFIX")

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(directory)
        applyPosixPermissions(directory, POSIX_DIR_PERMS)
        val temp = directory.resolve("${target.fileName}$TEMP_SUFFIX")
        Files.write(
            temp,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        applyPosixPermissions(temp, POSIX_FILE_PERMS)
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        applyPosixPermissions(target, POSIX_FILE_PERMS)
    }

    private fun applyPosixPermissions(path: Path, perms: String) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
        } catch (_: IOException) {
            // Non-POSIX or restricted filesystems fall back to Android app sandbox permissions.
        }
    }

    private companion object {
        const val FILE_SUFFIX = ".vck"
        const val TEMP_SUFFIX = ".tmp"
        const val POSIX_DIR_PERMS = "rwx------"
        const val POSIX_FILE_PERMS = "rw-------"
    }
}

internal object KeyStoreAliasRules {
    private const val MAX_ALIAS_LEN = 64
    private const val MAX_SECRET_BYTES = 256
    private const val FILE_VERSION = 1
    private val MAGIC =
        byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte())
    private val ALIAS_PATTERN = Regex("^[A-Za-z0-9._-]{1,$MAX_ALIAS_LEN}$")

    fun isValidAlias(alias: String): Boolean =
        ALIAS_PATTERN.matches(alias) && alias != "." && alias != ".."

    fun validateAlias(alias: String) {
        require(isValidAlias(alias)) {
            "Invalid KeyStore alias '$alias'; allowed [A-Za-z0-9._-]{1,$MAX_ALIAS_LEN}"
        }
    }

    fun validateSecret(secret: ByteArray) {
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES) {
            "Secret size must be in 1..$MAX_SECRET_BYTES bytes, got ${secret.size}"
        }
    }

    fun buildFileHeaderAad(alias: String): ByteArray {
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val out = ByteArray(MAGIC.size + 2 + aliasBytes.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = FILE_VERSION.toByte()
        out[MAGIC.size + 1] = aliasBytes.size.toByte()
        aliasBytes.copyInto(out, MAGIC.size + 2)
        return out
    }
}
