package com.mastersoft.velocast.protocol

import java.io.Closeable
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bounded byte range descriptor used by zero-copy [SessionCipher] operations. */
class ByteSlice(val bytes: ByteArray, val offset: Int = 0, val length: Int = bytes.size - offset)

/**
 * Unidirectional stream direction, defining the 4-byte prefix of the 96-bit AES-GCM counter nonce
 * (`4-byte direction prefix + 8-byte counter`) and domain-separated HKDF rekey info string.
 */
enum class CipherDirection(prefixAscii: String, rekeyLabel: String) {
    CLIENT_TO_SERVER("C2S\u0000", "VLC2-v2-rekey-c2s"),
    SERVER_TO_CLIENT("S2C\u0000", "VLC2-v2-rekey-s2c")
    ;

    private val prefix = prefixAscii.toByteArray(Charsets.US_ASCII)
    internal val rekeyInfo = rekeyLabel.toByteArray(Charsets.US_ASCII)

    init {
        check(prefix.size == ProtocolV2.AEAD_DIRECTION_PREFIX_SIZE)
    }

    /** Writes `prefix (4B) || u64be(counter) (8B)` into [dest] (`12` bytes) and returns [dest]. */
    fun buildNonce(
        counter: Long,
        dest: ByteArray = ByteArray(ProtocolV2.AEAD_NONCE_SIZE)
    ): ByteArray {
        require(dest.size >= ProtocolV2.AEAD_NONCE_SIZE) { "nonce buffer too small" }
        prefix.copyInto(dest, 0)
        BigEndian.putI64(dest, ProtocolV2.AEAD_DIRECTION_PREFIX_SIZE, counter)
        return dest
    }
}

/**
 * Stateful unidirectional per-frame AEAD cipher (`AES-256-GCM`) with 96-bit counter nonces,
 * header bytes bound as associated data (`AAD`), strict counter monotonicity, and key rotation
 * before `2^32` frames (`docs/security.md`).
 */
interface SessionCipher : Closeable {
    val direction: CipherDirection
    val currentEpoch: Int
    val nextCounter: Long
    val needsRekey: Boolean

    fun seal(
        aad: ByteArray,
        plaintext: ByteArray,
        plaintextOffset: Int = 0,
        plaintextLength: Int = plaintext.size - plaintextOffset
    ): ByteArray {
        val out = ByteArray(plaintextLength + ProtocolV2.AEAD_TAG_SIZE)
        sealInto(
            aad = ByteSlice(aad),
            plaintext = ByteSlice(plaintext, plaintextOffset, plaintextLength),
            dest = out,
            destOffset = 0
        )
        return out
    }

    fun sealInto(aad: ByteSlice, plaintext: ByteSlice, dest: ByteArray, destOffset: Int): Int

    /**
     * Seals using an explicit [counter]. Enforces strict counter equality (`counter == nextCounter`)
     * so any attempt to reuse an earlier counter or skip ahead is rejected immediately.
     */
    fun sealWithCounter(counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray

    fun open(
        epoch: Int,
        counter: Long,
        aad: ByteArray,
        ciphertextWithTag: ByteArray,
        offset: Int = 0,
        length: Int = ciphertextWithTag.size - offset
    ): ByteArray {
        if (length < ProtocolV2.AEAD_TAG_SIZE) {
            fail(ErrorCode.MALFORMED_MESSAGE, "AEAD ciphertext shorter than authentication tag")
        }
        val out = ByteArray(length - ProtocolV2.AEAD_TAG_SIZE)
        openInto(
            epoch = epoch,
            counter = counter,
            aad = ByteSlice(aad),
            ciphertext = ByteSlice(ciphertextWithTag, offset, length),
            dest = out,
            destOffset = 0
        )
        return out
    }

    fun openInto(
        epoch: Int,
        counter: Long,
        aad: ByteSlice,
        ciphertext: ByteSlice,
        dest: ByteArray,
        destOffset: Int
    ): Int

    /** Rotates to `epoch + 1`, zeroizes the current key, and resets [nextCounter] to `0`. */
    fun ratchetKey()
}

/**
 * JCA `AES/GCM/NoPadding` implementation of [SessionCipher] (256-bit key, 128-bit tag, 96-bit IV).
 */
class AesGcmSessionCipher(
    initialKey: ByteArray,
    override val direction: CipherDirection,
    val rekeyAfterFrames: Long = DEFAULT_REKEY_AFTER_FRAMES
) : SessionCipher {
    private var keyBytes: ByteArray
    private var keySpec: SecretKeySpec
    private val nonceScratch = ByteArray(ProtocolV2.AEAD_NONCE_SIZE)
    private val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
    private var closed = false

    override var currentEpoch: Int = 0
        private set

    override var nextCounter: Long = 0L
        private set

    init {
        require(initialKey.size == ProtocolV2.SECRET_SIZE) {
            "AES-256-GCM key must be ${ProtocolV2.SECRET_SIZE} bytes, got ${initialKey.size}"
        }
        require(rekeyAfterFrames in 1L until MAX_FRAMES_PER_EPOCH) {
            "rekeyAfterFrames must be in 1..<$MAX_FRAMES_PER_EPOCH, got $rekeyAfterFrames"
        }
        keyBytes = initialKey.copyOf()
        keySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    override val needsRekey: Boolean
        get() = nextCounter >= rekeyAfterFrames

    override fun sealInto(
        aad: ByteSlice,
        plaintext: ByteSlice,
        dest: ByteArray,
        destOffset: Int
    ): Int = executeSeal(nextCounter, aad, plaintext, dest, destOffset)

    override fun sealWithCounter(counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + ProtocolV2.AEAD_TAG_SIZE)
        executeSeal(counter, ByteSlice(aad), ByteSlice(plaintext), out, 0)
        return out
    }

    private fun executeSeal(
        requestedCounter: Long,
        aad: ByteSlice,
        plaintext: ByteSlice,
        dest: ByteArray,
        destOffset: Int
    ): Int {
        ensureOpen()
        CipherSliceRules.validateSeal(aad, plaintext, dest, destOffset)
        CipherSliceRules.validateSealCounter(requestedCounter, nextCounter)
        direction.buildNonce(requestedCounter, nonceScratch)
        try {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(GCM_TAG_BITS, nonceScratch)
            )
            if (aad.length > 0) {
                cipher.updateAAD(aad.bytes, aad.offset, aad.length)
            }
            val written = cipher.doFinal(
                plaintext.bytes,
                plaintext.offset,
                plaintext.length,
                dest,
                destOffset
            )
            nextCounter = requestedCounter + 1L
            return written
        } catch (e: GeneralSecurityException) {
            fail(ErrorCode.AUTH_FAILED, "AEAD seal failed: ${e.message}")
        }
    }

    override fun openInto(
        epoch: Int,
        counter: Long,
        aad: ByteSlice,
        ciphertext: ByteSlice,
        dest: ByteArray,
        destOffset: Int
    ): Int {
        ensureOpen()
        CipherSliceRules.validateOpen(aad, ciphertext, dest, destOffset)
        CipherSliceRules.validateInboundOrdering(epoch, currentEpoch, counter, nextCounter)
        direction.buildNonce(counter, nonceScratch)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(GCM_TAG_BITS, nonceScratch)
            )
            if (aad.length > 0) {
                cipher.updateAAD(aad.bytes, aad.offset, aad.length)
            }
            val plainLen = cipher.doFinal(
                ciphertext.bytes,
                ciphertext.offset,
                ciphertext.length,
                dest,
                destOffset
            )
            nextCounter = counter + 1L
            return plainLen
        } catch (_: GeneralSecurityException) {
            fail(ErrorCode.AUTH_FAILED, "AEAD authentication failed")
        }
    }

    override fun ratchetKey() {
        ensureOpen()
        if (currentEpoch >= MAX_EPOCH) {
            fail(ErrorCode.AUTH_FAILED, "AEAD epoch space exhausted; reconnect required")
        }
        val nextEpoch = currentEpoch + 1
        val derived = SessionKeySchedule.deriveRekey(keyBytes, direction, nextEpoch)
        keyBytes.fill(0)
        keyBytes = derived
        keySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
        currentEpoch = nextEpoch
        nextCounter = 0L
    }

    override fun close() {
        closed = true
        keyBytes.fill(0)
        nonceScratch.fill(0)
    }

    override fun toString(): String =
        "AesGcmSessionCipher(direction=$direction, epoch=$currentEpoch, <redacted>)"

    private fun ensureOpen() {
        if (closed) fail(ErrorCode.AUTH_FAILED, "SessionCipher is closed")
    }

    companion object {
        const val MAX_FRAMES_PER_EPOCH: Long = 1L shl 32
        const val DEFAULT_REKEY_AFTER_FRAMES: Long = MAX_FRAMES_PER_EPOCH - 256L
        const val MAX_EPOCH: Int = 0xFFFF
        private const val GCM_TAG_BITS = 128
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private object CipherSliceRules {
    fun validateSlice(slice: ByteSlice, label: String) {
        require(slice.offset >= 0 && slice.length >= 0) { "negative $label offset/length" }
        require(slice.length <= slice.bytes.size - slice.offset) { "$label out of bounds" }
    }

    fun validateSeal(aad: ByteSlice, plaintext: ByteSlice, dest: ByteArray, destOffset: Int) {
        validateSlice(aad, "aad")
        validateSlice(plaintext, "plaintext")
        val needed = plaintext.length + ProtocolV2.AEAD_TAG_SIZE
        require(destOffset >= 0 && dest.size - destOffset >= needed) { "dest buffer too small" }
    }

    fun validateOpen(aad: ByteSlice, ciphertext: ByteSlice, dest: ByteArray, destOffset: Int) {
        validateSlice(aad, "aad")
        validateSlice(ciphertext, "ciphertext")
        if (ciphertext.length < ProtocolV2.AEAD_TAG_SIZE) {
            fail(ErrorCode.MALFORMED_MESSAGE, "AEAD ciphertext shorter than authentication tag")
        }
        val plainLen = ciphertext.length - ProtocolV2.AEAD_TAG_SIZE
        require(destOffset >= 0 && dest.size - destOffset >= plainLen) { "dest buffer too small" }
    }

    fun validateSealCounter(requestedCounter: Long, nextCounter: Long) {
        if (requestedCounter < nextCounter) {
            fail(
                ErrorCode.AUTH_FAILED,
                "AEAD nonce reuse rejected: counter $requestedCounter < $nextCounter"
            )
        }
        if (requestedCounter != nextCounter) {
            fail(
                ErrorCode.AUTH_FAILED,
                "AEAD non-monotonic counter rejected: $requestedCounter != $nextCounter"
            )
        }
        if (requestedCounter >= AesGcmSessionCipher.MAX_FRAMES_PER_EPOCH) {
            fail(ErrorCode.AUTH_FAILED, "AEAD 2^32 frame limit reached; rekey required")
        }
    }

    fun validateInboundOrdering(
        epoch: Int,
        expectedEpoch: Int,
        counter: Long,
        expectedCounter: Long
    ) {
        if (epoch != expectedEpoch) {
            fail(
                ErrorCode.AUTH_FAILED,
                "AEAD epoch mismatch: expected $expectedEpoch, got $epoch"
            )
        }
        if (counter < 0L || counter < expectedCounter) {
            fail(
                ErrorCode.AUTH_FAILED,
                "AEAD replay rejected: counter $counter < expected $expectedCounter"
            )
        }
        if (counter > expectedCounter) {
            fail(
                ErrorCode.AUTH_FAILED,
                "AEAD reordered frame rejected: counter $counter > expected $expectedCounter"
            )
        }
        if (counter >= AesGcmSessionCipher.MAX_FRAMES_PER_EPOCH) {
            fail(ErrorCode.AUTH_FAILED, "AEAD counter exhausted")
        }
    }
}
