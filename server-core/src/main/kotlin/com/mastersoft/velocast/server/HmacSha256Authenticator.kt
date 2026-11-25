package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.ProtocolV2
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Temporary S03A HMAC-SHA256 [Authenticator] backed by the secret delivered on stdin.
 * Step S06 replaces this with the HKDF-derived production authenticator behind [Authenticator].
 */
class HmacSha256Authenticator(secret: ByteArray) : Authenticator {
    private val keySpec: SecretKeySpec

    init {
        require(secret.size >= MIN_SECRET_BYTES) {
            "Secret must be at least $MIN_SECRET_BYTES bytes"
        }
        val copy = secret.copyOf()
        try {
            keySpec = SecretKeySpec(copy, ALGORITHM)
        } finally {
            copy.fill(0)
        }
    }

    override fun computeProof(transcript: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(transcript)
    }

    override fun verifyProof(transcript: ByteArray, proof: ByteArray): Boolean =
        proof.size == ProtocolV2.PROOF_SIZE &&
            MessageDigest.isEqual(computeProof(transcript), proof)

    override fun toString(): String = "HmacSha256Authenticator(<redacted>)"

    companion object {
        const val MIN_SECRET_BYTES = 16
        const val MAX_SECRET_BYTES = 64
        private const val ALGORITHM = "HmacSHA256"
    }
}

/**
 * Reads a single newline-terminated (or EOF-terminated) hex secret line from [input] without
 * reading past `\n`, so launchers that keep stdin open do not deadlock. Never logs secret bytes.
 */
object SecretReader {
    private const val MAX_LINE_BYTES = HmacSha256Authenticator.MAX_SECRET_BYTES * 2 + 2
    private const val HIGH_NIBBLE_SHIFT = 4
    private const val TEN = 10

    fun readHexLine(input: InputStream): ByteArray {
        val lineBuf = ByteArray(MAX_LINE_BYTES)
        var length = 0
        try {
            var next = input.read()
            while (next != -1 && next != '\n'.code) {
                if (length >= lineBuf.size) {
                    throw IllegalArgumentException("Secret on stdin exceeds maximum length")
                }
                lineBuf[length++] = next.toByte()
                next = input.read()
            }
            if (length > 0 && lineBuf[length - 1] == '\r'.code.toByte()) {
                length--
            }
            return decodeHex(lineBuf, length)
        } finally {
            lineBuf.fill(0)
        }
    }

    private fun decodeHex(buf: ByteArray, length: Int): ByteArray {
        if (length == 0 || length % 2 != 0) {
            throw IllegalArgumentException("Secret on stdin must be an even-length hex string")
        }
        val byteLen = length / 2
        if (byteLen < HmacSha256Authenticator.MIN_SECRET_BYTES ||
            byteLen > HmacSha256Authenticator.MAX_SECRET_BYTES
        ) {
            throw IllegalArgumentException(
                "Secret on stdin must decode to " +
                    "${HmacSha256Authenticator.MIN_SECRET_BYTES}.." +
                    "${HmacSha256Authenticator.MAX_SECRET_BYTES} bytes"
            )
        }
        val out = ByteArray(byteLen)
        for (i in 0 until byteLen) {
            val hi = hexNibble(buf[i * 2].toInt())
            val lo = hexNibble(buf[i * 2 + 1].toInt())
            out[i] = ((hi shl HIGH_NIBBLE_SHIFT) or lo).toByte()
        }
        return out
    }

    private fun hexNibble(ch: Int): Int = when (ch) {
        in '0'.code..'9'.code -> ch - '0'.code
        in 'a'.code..'f'.code -> ch - 'a'.code + TEN
        in 'A'.code..'F'.code -> ch - 'A'.code + TEN
        else -> throw IllegalArgumentException("Secret on stdin contains non-hex characters")
    }
}
