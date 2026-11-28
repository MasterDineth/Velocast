package com.mastersoft.velocast.launcher.adb

import android.content.Context
import com.mastersoft.velocast.client.AndroidKeyStoreEnvelopeCipher
import com.mastersoft.velocast.protocol.ConstantTime
import com.mastersoft.velocast.protocol.EnvelopeCipher
import com.mastersoft.velocast.protocol.KeyStoreSecurityException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Client RSA-2048 key pair, self-signed X.509v3 certificate, and encoded Android `adb_keys` entry
 * used by [AdbClient] for Wireless Debugging pairing, `STLS` TLS 1.3 mutual authentication, and
 * legacy `A_AUTH` connections.
 */
class AdbKeyIdentity internal constructor(
    val alias: String,
    internal val privateKey: PrivateKey,
    val publicKey: RSAPublicKey,
    val certificate: X509Certificate,
    val ownerName: String = AndroidPubkeyCodec.DEFAULT_OWNER_NAME
) {
    val sha256FingerprintHex: String = computeSha256Hex(certificate.encoded)

    fun encodedAuthorizedKey(): ByteArray =
        AndroidPubkeyCodec.encodeAuthorizedKey(publicKey, ownerName)

    override fun toString(): String =
        "AdbKeyIdentity(alias=$alias, owner=$ownerName, certSha256=$sha256FingerprintHex)"

    private companion object {
        const val BYTE_MASK = 0xFF
        const val HIGH_NIBBLE_SHIFT = 4
        const val LOW_NIBBLE_MASK = 0x0F
        private val HEX = "0123456789abcdef".toCharArray()

        fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val chars = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val b = digest[i].toInt() and BYTE_MASK
                chars[i * 2] = HEX[b ushr HIGH_NIBBLE_SHIFT]
                chars[i * 2 + 1] = HEX[b and LOW_NIBBLE_MASK]
            }
            return String(chars)
        }
    }
}

/**
 * Persistence contract for [AdbKeyIdentity] credentials.
 */
interface AdbKeyStore {
    fun getOrCreateIdentity(
        alias: String = DEFAULT_KEY_ALIAS,
        ownerName: String = AndroidPubkeyCodec.DEFAULT_OWNER_NAME
    ): AdbKeyIdentity

    fun loadIdentity(alias: String = DEFAULT_KEY_ALIAS): AdbKeyIdentity?

    fun hasIdentity(alias: String = DEFAULT_KEY_ALIAS): Boolean

    fun deleteIdentity(alias: String = DEFAULT_KEY_ALIAS): Boolean

    fun listAliases(): Set<String>

    companion object {
        const val DEFAULT_KEY_ALIAS = "default_adb_key"
    }
}

/**
 * Generates RSA-2048 key pairs and self-signed X.509v3 certificates using JCA + Bouncy Castle PKIX.
 */
internal object AdbCertificateGenerator {
    private const val RSA_BITS = 2048
    private const val SERIAL_BITS = 128
    private const val CERT_VALIDITY_DAYS = 3650L
    private const val CLOCK_SKEW_MINUTES = 5L
    private const val SUBJECT_DN = "CN=VeloCast Viewer,O=VeloCast"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    fun generate(
        alias: String,
        ownerName: String = AndroidPubkeyCodec.DEFAULT_OWNER_NAME,
        random: SecureRandom = SecureRandom()
    ): AdbKeyIdentity {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4), random)
        val keyPair = kpg.generateKeyPair()
        val rsaPublic = keyPair.public as RSAPublicKey

        val now = Instant.now()
        val notBefore = Date.from(now.minus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES))
        val notAfter = Date.from(now.plus(CERT_VALIDITY_DAYS, ChronoUnit.DAYS))
        val serial = BigInteger(SERIAL_BITS, random).abs().max(BigInteger.ONE)
        val issuer = X500Name(SUBJECT_DN)
        val spki = SubjectPublicKeyInfo.getInstance(rsaPublic.encoded)
        val skiDigest = MessageDigest.getInstance("SHA-1").digest(rsaPublic.encoded)

        val builder = X509v3CertificateBuilder(issuer, serial, notBefore, notAfter, issuer, spki)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            .addExtension(
                Extension.subjectKeyIdentifier,
                false,
                SubjectKeyIdentifier(skiDigest)
            )

        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setSecureRandom(random)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        return AdbKeyIdentity(
            alias = alias,
            privateKey = keyPair.private,
            publicKey = rsaPublic,
            certificate = certificate,
            ownerName = ownerName
        )
    }
}

/**
 * Keystore-protected [AdbKeyStore] persisting encrypted `<alias>.adbkey` envelopes exclusively
 * under `Context.noBackupFilesDir` sealed by [AndroidKeyStoreEnvelopeCipher] (`AndroidKeyStore`
 * 256-bit `AES/GCM/NoPadding`), binding `"VLCA" || version || alias` into the AEAD AAD.
 */
class KeystoreProtectedAdbKeyStore(
    private val directory: Path,
    private val envelopeCipher: EnvelopeCipher,
    private val random: SecureRandom = SecureRandom()
) : AdbKeyStore {
    private val lock = Any()

    constructor(
        context: Context,
        subDirectory: String = DEFAULT_SUBDIRECTORY,
        masterKeyAlias: String = DEFAULT_MASTER_KEY_ALIAS
    ) : this(
        directory = context.applicationContext.noBackupFilesDir.toPath().resolve(subDirectory),
        envelopeCipher = AndroidKeyStoreEnvelopeCipher(masterKeyAlias)
    )

    override fun getOrCreateIdentity(alias: String, ownerName: String): AdbKeyIdentity {
        validateAlias(alias)
        return synchronized(lock) {
            loadUnlocked(alias) ?: generateAndSaveUnlocked(alias, ownerName)
        }
    }

    override fun loadIdentity(alias: String): AdbKeyIdentity? {
        validateAlias(alias)
        return synchronized(lock) { loadUnlocked(alias) }
    }

    override fun hasIdentity(alias: String): Boolean {
        validateAlias(alias)
        return synchronized(lock) { Files.isRegularFile(entryPath(alias)) }
    }

    override fun deleteIdentity(alias: String): Boolean {
        validateAlias(alias)
        return synchronized(lock) { Files.deleteIfExists(entryPath(alias)) }
    }

    override fun listAliases(): Set<String> = synchronized(lock) {
        if (!Files.isDirectory(directory)) return emptySet()
        val aliases = ArrayList<String>()
        Files.newDirectoryStream(directory, "*$FILE_SUFFIX").use { stream ->
            for (path in stream) {
                val candidate = path.fileName.toString().removeSuffix(FILE_SUFFIX)
                if (isValidAlias(candidate)) aliases += candidate
            }
        }
        aliases.sorted().toCollection(LinkedHashSet())
    }

    private fun generateAndSaveUnlocked(alias: String, ownerName: String): AdbKeyIdentity {
        val identity = AdbCertificateGenerator.generate(alias, ownerName, random)
        val aad = AdbKeyRecordCodec.buildAadHeader(alias)
        val payload = AdbKeyRecordCodec.encodePlaintext(identity)
        val ciphertext = try {
            envelopeCipher.encrypt(aad, payload)
        } catch (e: GeneralSecurityException) {
            throw KeyStoreSecurityException("Failed encrypting ADB key identity '$alias'", e)
        } finally {
            payload.fill(0)
        }
        val fullFile = ByteArray(aad.size + ciphertext.size)
        aad.copyInto(fullFile, 0)
        ciphertext.copyInto(fullFile, aad.size)
        AdbKeyRecordCodec.writeAtomically(directory, entryPath(alias), fullFile)
        return identity
    }

    private fun loadUnlocked(alias: String): AdbKeyIdentity? {
        val path = entryPath(alias)
        if (!Files.isRegularFile(path)) return null
        val raw = Files.readAllBytes(path)
        val aad = AdbKeyRecordCodec.buildAadHeader(alias)
        val validHeader = raw.size > aad.size &&
            raw.size <= MAX_ENVELOPE_FILE_BYTES &&
            ConstantTime.equals(aad, raw.copyOfRange(0, aad.size))
        if (!validHeader) {
            throw KeyStoreSecurityException("Tampered or invalid ADB key header for alias '$alias'")
        }
        val plaintext = try {
            envelopeCipher.decrypt(aad, raw.copyOfRange(aad.size, raw.size))
        } catch (e: GeneralSecurityException) {
            throw KeyStoreSecurityException("Failed authenticating ADB key alias '$alias'", e)
        }
        return try {
            AdbKeyRecordCodec.decodePlaintext(alias, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun entryPath(alias: String): Path = directory.resolve("$alias$FILE_SUFFIX")

    companion object {
        const val DEFAULT_SUBDIRECTORY = "velocast_adb_keys"
        const val DEFAULT_MASTER_KEY_ALIAS = "com.mastersoft.velocast.viewer.adb_master_key.v1"

        private const val FILE_SUFFIX = ".adbkey"
        private const val MAX_ALIAS_LENGTH = 64
        private const val MAX_ENVELOPE_FILE_BYTES = 16_384
        private val ALIAS_REGEX = Regex("^[A-Za-z0-9._-]{1,$MAX_ALIAS_LENGTH}$")

        internal fun isValidAlias(alias: String): Boolean =
            ALIAS_REGEX.matches(alias) && alias != "." && alias != ".."

        internal fun validateAlias(alias: String) {
            require(isValidAlias(alias)) { "Invalid ADB key alias '$alias'" }
        }
    }
}

private object AdbKeyRecordCodec {
    private const val HEADER_VERSION: Byte = 1
    private const val RECORD_FORMAT_VERSION: Byte = 1
    private const val TEMP_SUFFIX = ".tmp"
    private const val POSIX_DIR_PERMS = "rwx------"
    private const val POSIX_FILE_PERMS = "rw-------"
    private const val RECORD_LENGTH_FIELDS = 3
    private const val MAX_KEY_DER_BYTES = 4_096
    private const val MAX_CERT_DER_BYTES = 8_192
    private const val MAX_OWNER_BYTES = 256
    private val MAGIC = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'C'.code.toByte(),
        'A'.code.toByte()
    )

    fun buildAadHeader(alias: String): ByteArray {
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val out = ByteArray(MAGIC.size + 2 + aliasBytes.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = HEADER_VERSION
        out[MAGIC.size + 1] = aliasBytes.size.toByte()
        aliasBytes.copyInto(out, MAGIC.size + 2)
        return out
    }

    fun encodePlaintext(identity: AdbKeyIdentity): ByteArray {
        val pkcs8 = identity.privateKey.encoded
            ?: throw KeyStoreSecurityException("ADB RSA PrivateKey did not expose PKCS#8 encoding")
        val certDer = identity.certificate.encoded
        val ownerBytes = identity.ownerName.toByteArray(Charsets.UTF_8)
        try {
            val total = 1 + Int.SIZE_BYTES * RECORD_LENGTH_FIELDS +
                pkcs8.size + certDer.size + ownerBytes.size
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(RECORD_FORMAT_VERSION)
            buf.putInt(pkcs8.size)
            buf.put(pkcs8)
            buf.putInt(certDer.size)
            buf.put(certDer)
            buf.putInt(ownerBytes.size)
            buf.put(ownerBytes)
            return buf.array()
        } finally {
            pkcs8.fill(0)
        }
    }

    fun decodePlaintext(alias: String, bytes: ByteArray): AdbKeyIdentity = try {
        parsePlaintextBuffer(alias, bytes)
    } catch (e: GeneralSecurityException) {
        if (e is KeyStoreSecurityException) throw e
        throw KeyStoreSecurityException("Corrupted ADB key record for alias '$alias'", e)
    } catch (e: BufferUnderflowException) {
        throw KeyStoreSecurityException("Truncated ADB key record for alias '$alias'", e)
    } catch (e: IllegalArgumentException) {
        throw KeyStoreSecurityException("Malformed ADB key record for alias '$alias'", e)
    }

    private fun parsePlaintextBuffer(alias: String, bytes: ByteArray): AdbKeyIdentity {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buf.remaining() > 0 && buf.get() == RECORD_FORMAT_VERSION) {
            "Unsupported ADB key record version"
        }
        val pkcs8 = readBoundedSlice(buf, MAX_KEY_DER_BYTES)
        val certDer = readBoundedSlice(buf, MAX_CERT_DER_BYTES)
        val ownerBytes = readBoundedSlice(buf, MAX_OWNER_BYTES)
        val privateKey = try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } finally {
            pkcs8.fill(0)
        }
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        val rsaPublic = requireNotNull(cert.publicKey as? RSAPublicKey) {
            "Stored ADB certificate does not carry RSAPublicKey"
        }
        return AdbKeyIdentity(
            alias = alias,
            privateKey = privateKey,
            publicKey = rsaPublic,
            certificate = cert,
            ownerName = String(ownerBytes, Charsets.UTF_8)
        )
    }

    private fun readBoundedSlice(buf: ByteBuffer, maxBytes: Int): ByteArray {
        require(buf.remaining() >= Int.SIZE_BYTES) { "Truncated ADB key record length" }
        val len = buf.int
        require(len in 1..maxBytes && len <= buf.remaining()) {
            "Invalid ADB key field length: $len"
        }
        return ByteArray(len).also { buf.get(it) }
    }

    fun writeAtomically(directory: Path, target: Path, bytes: ByteArray) {
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
            // Non-POSIX filesystems rely on Android app-private sandbox permissions.
        }
    }
}

/** In-memory [AdbKeyStore] implementation for unit testing. */
class InMemoryAdbKeyStore(private val random: SecureRandom = SecureRandom()) : AdbKeyStore {
    private val lock = Any()
    private val map = LinkedHashMap<String, AdbKeyIdentity>()

    override fun getOrCreateIdentity(alias: String, ownerName: String): AdbKeyIdentity {
        KeystoreProtectedAdbKeyStore.validateAlias(alias)
        return synchronized(lock) {
            map.getOrPut(alias) { AdbCertificateGenerator.generate(alias, ownerName, random) }
        }
    }

    override fun loadIdentity(alias: String): AdbKeyIdentity? {
        KeystoreProtectedAdbKeyStore.validateAlias(alias)
        return synchronized(lock) { map[alias] }
    }

    override fun hasIdentity(alias: String): Boolean {
        KeystoreProtectedAdbKeyStore.validateAlias(alias)
        return synchronized(lock) { map.containsKey(alias) }
    }

    override fun deleteIdentity(alias: String): Boolean {
        KeystoreProtectedAdbKeyStore.validateAlias(alias)
        return synchronized(lock) { map.remove(alias) != null }
    }

    override fun listAliases(): Set<String> =
        synchronized(lock) { map.keys.sorted().toCollection(LinkedHashSet()) }
}
