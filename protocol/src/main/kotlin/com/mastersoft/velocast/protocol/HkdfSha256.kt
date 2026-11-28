package com.mastersoft.velocast.protocol

import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Constant-time byte array comparison backed by JDK [MessageDigest.isEqual]. */
object ConstantTime {
    fun equals(expected: ByteArray, actual: ByteArray): Boolean =
        expected.size == actual.size && MessageDigest.isEqual(expected, actual)
}

/** CSPRNG generator for 256-bit `SESSION_SECRET` and `PAIRING_SECRET` values. */
object SessionSecrets {
    private val defaultRandom = SecureRandom()

    fun generate(random: SecureRandom = defaultRandom): ByteArray =
        ByteArray(ProtocolV2.SECRET_SIZE).also { random.nextBytes(it) }
}

/**
 * Pure-JVM RFC 5869 `HKDF-SHA256` built exclusively on standard `javax.crypto.Mac` (`HmacSHA256`).
 */
object HkdfSha256 {
    const val HASH_LEN = 32
    const val MAX_BLOCKS = 255
    const val MAX_OKM_LEN = MAX_BLOCKS * HASH_LEN
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val EMPTY_BYTES = ByteArray(0)

    /** RFC 5869 Section 2.2: `HKDF-Extract(salt, IKM) -> PRK` (32 bytes). */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 Section 2.3: `HKDF-Expand(PRK, info, L) -> OKM` (`0..8160` bytes). */
    fun expand(prk: ByteArray, info: ByteArray = EMPTY_BYTES, length: Int = HASH_LEN): ByteArray {
        require(prk.size >= HASH_LEN) { "PRK must be at least $HASH_LEN bytes, got ${prk.size}" }
        require(length in 0..MAX_OKM_LEN) { "HKDF length $length out of range 0..$MAX_OKM_LEN" }
        if (length == 0) return EMPTY_BYTES

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val out = ByteArray(length)
        var prevBlock = EMPTY_BYTES
        var generated = 0
        var blockIndex = 1
        try {
            while (generated < length) {
                mac.reset()
                if (prevBlock.isNotEmpty()) {
                    mac.update(prevBlock)
                }
                if (info.isNotEmpty()) {
                    mac.update(info)
                }
                mac.update(blockIndex.toByte())
                val nextBlock = mac.doFinal()
                prevBlock.fill(0)
                prevBlock = nextBlock

                val toCopy = minOf(HASH_LEN, length - generated)
                nextBlock.copyInto(out, destinationOffset = generated, endIndex = toCopy)
                generated += toCopy
                blockIndex++
            }
        } finally {
            prevBlock.fill(0)
        }
        return out
    }

    /** Convenience one-shot `HKDF-Extract` + `HKDF-Expand` that zeroizes the intermediate `PRK`. */
    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray = EMPTY_BYTES,
        length: Int = HASH_LEN
    ): ByteArray {
        val prk = extract(salt, ikm)
        return try {
            expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }
}

/**
 * Directional 256-bit AEAD keys derived for one session. Calling [close] zeroizes both key arrays.
 */
class SessionKeys(clientToServerKey: ByteArray, serverToClientKey: ByteArray) : Closeable {
    private val c2sKey = clientToServerKey.copyOf()
    private val s2cKey = serverToClientKey.copyOf()

    @Volatile
    private var closed = false

    init {
        require(c2sKey.size == ProtocolV2.SECRET_SIZE) {
            "clientToServerKey must be ${ProtocolV2.SECRET_SIZE} bytes"
        }
        require(s2cKey.size == ProtocolV2.SECRET_SIZE) {
            "serverToClientKey must be ${ProtocolV2.SECRET_SIZE} bytes"
        }
    }

    fun copyClientToServerKey(): ByteArray {
        check(!closed) { "SessionKeys already closed" }
        return c2sKey.copyOf()
    }

    fun copyServerToClientKey(): ByteArray {
        check(!closed) { "SessionKeys already closed" }
        return s2cKey.copyOf()
    }

    fun newClientOutboundCipher(
        rekeyAfterFrames: Long = AesGcmSessionCipher.DEFAULT_REKEY_AFTER_FRAMES
    ): SessionCipher {
        check(!closed) { "SessionKeys already closed" }
        return AesGcmSessionCipher(c2sKey, CipherDirection.CLIENT_TO_SERVER, rekeyAfterFrames)
    }

    fun newClientInboundCipher(
        rekeyAfterFrames: Long = AesGcmSessionCipher.DEFAULT_REKEY_AFTER_FRAMES
    ): SessionCipher {
        check(!closed) { "SessionKeys already closed" }
        return AesGcmSessionCipher(s2cKey, CipherDirection.SERVER_TO_CLIENT, rekeyAfterFrames)
    }

    fun newServerInboundCipher(
        rekeyAfterFrames: Long = AesGcmSessionCipher.DEFAULT_REKEY_AFTER_FRAMES
    ): SessionCipher {
        check(!closed) { "SessionKeys already closed" }
        return AesGcmSessionCipher(c2sKey, CipherDirection.CLIENT_TO_SERVER, rekeyAfterFrames)
    }

    fun newServerOutboundCipher(
        rekeyAfterFrames: Long = AesGcmSessionCipher.DEFAULT_REKEY_AFTER_FRAMES
    ): SessionCipher {
        check(!closed) { "SessionKeys already closed" }
        return AesGcmSessionCipher(s2cKey, CipherDirection.SERVER_TO_CLIENT, rekeyAfterFrames)
    }

    override fun close() {
        closed = true
        c2sKey.fill(0)
        s2cKey.fill(0)
    }

    override fun toString(): String = "SessionKeys(<redacted>)"
}

/**
 * Domain-separated HKDF-SHA256 key schedule for VeloCast protocol v2 (`docs/security.md`).
 */
object SessionKeySchedule {
    private val INFO_HANDSHAKE_AUTH = "VLC2-v2-handshake-auth".toByteArray(Charsets.US_ASCII)
    private val INFO_C2S_KEY = "VLC2-v2-c2s-key".toByteArray(Charsets.US_ASCII)
    private val INFO_S2C_KEY = "VLC2-v2-s2c-key".toByteArray(Charsets.US_ASCII)
    private const val EPOCH_SALT_BYTES = 2

    fun deriveAuthKey(
        secret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): ByteArray {
        validateInputs(secret, clientNonce, serverNonce)
        val salt = clientNonce + serverNonce
        return HkdfSha256.deriveKey(
            ikm = secret,
            salt = salt,
            info = INFO_HANDSHAKE_AUTH,
            length = ProtocolV2.SECRET_SIZE
        )
    }

    fun deriveSessionKeys(
        secret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): SessionKeys {
        validateInputs(secret, clientNonce, serverNonce)
        val salt = clientNonce + serverNonce
        val prk = HkdfSha256.extract(salt, secret)
        var c2s: ByteArray? = null
        var s2c: ByteArray? = null
        return try {
            c2s = HkdfSha256.expand(prk, INFO_C2S_KEY, ProtocolV2.SECRET_SIZE)
            s2c = HkdfSha256.expand(prk, INFO_S2C_KEY, ProtocolV2.SECRET_SIZE)
            SessionKeys(c2s, s2c)
        } finally {
            prk.fill(0)
            c2s?.fill(0)
            s2c?.fill(0)
        }
    }

    fun deriveRekey(currentKey: ByteArray, direction: CipherDirection, nextEpoch: Int): ByteArray {
        require(currentKey.size == ProtocolV2.SECRET_SIZE) {
            "currentKey must be ${ProtocolV2.SECRET_SIZE} bytes"
        }
        Validators.u16(nextEpoch, "nextEpoch")
        val salt = ByteArray(EPOCH_SALT_BYTES).also { BigEndian.putU16(it, 0, nextEpoch) }
        return HkdfSha256.deriveKey(
            ikm = currentKey,
            salt = salt,
            info = direction.rekeyInfo,
            length = ProtocolV2.SECRET_SIZE
        )
    }

    private fun validateInputs(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        require(secret.size == ProtocolV2.SECRET_SIZE) {
            "Secret must be ${ProtocolV2.SECRET_SIZE} bytes (256 bits), got ${secret.size}"
        }
        require(clientNonce.size == ProtocolV2.NONCE_SIZE) {
            "clientNonce must be ${ProtocolV2.NONCE_SIZE} bytes"
        }
        require(serverNonce.size == ProtocolV2.NONCE_SIZE) {
            "serverNonce must be ${ProtocolV2.NONCE_SIZE} bytes"
        }
    }
}

/**
 * Production [Authenticator] (`S06`): derives an authentication key from the 256-bit
 * `SESSION_SECRET` or `PAIRING_SECRET` and both handshake nonces via [HkdfSha256], signs the
 * `SHA-256` transcript hash with `HMAC-SHA256`, compares proofs in constant time, and derives
 * directional 256-bit `AES-256-GCM` session keys.
 */
class HkdfAuthenticator(secret: ByteArray, val mode: AuthMode = AuthMode.SESSION_SECRET) :
    Authenticator,
    Closeable {
    private val secretBytes: ByteArray

    @Volatile
    private var closed = false

    init {
        require(secret.size == ProtocolV2.SECRET_SIZE) {
            "Secret must be ${ProtocolV2.SECRET_SIZE} bytes (256 bits), got ${secret.size}"
        }
        secretBytes = secret.copyOf()
    }

    override val requiresSecureChannel: Boolean
        get() = true

    override fun computeProof(transcript: ByteArray): ByteArray {
        check(!closed) { "Authenticator has been closed" }
        val (clientNonce, serverNonce) = Transcript.extractNonces(transcript)
        val transcriptHash = Transcript.sha256(transcript)
        val authKey = SessionKeySchedule.deriveAuthKey(secretBytes, clientNonce, serverNonce)
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(authKey, HMAC_ALGORITHM))
            mac.doFinal(transcriptHash)
        } finally {
            authKey.fill(0)
        }
    }

    override fun verifyProof(transcript: ByteArray, proof: ByteArray): Boolean =
        proof.size == ProtocolV2.PROOF_SIZE &&
            ConstantTime.equals(computeProof(transcript), proof)

    override fun deriveSessionKeys(clientNonce: ByteArray, serverNonce: ByteArray): SessionKeys {
        check(!closed) { "Authenticator has been closed" }
        return SessionKeySchedule.deriveSessionKeys(secretBytes, clientNonce, serverNonce)
    }

    override fun close() {
        closed = true
        secretBytes.fill(0)
    }

    override fun toString(): String = "HkdfAuthenticator(mode=$mode, <redacted>)"

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
