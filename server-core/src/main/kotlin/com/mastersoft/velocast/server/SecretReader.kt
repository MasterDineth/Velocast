package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ProtocolV2
import java.io.InputStream

/**
 * Reads a single newline-terminated (or EOF-terminated) 256-bit (64-hex-char) secret line from
 * [input] without reading past `\n`, so launchers that keep stdin open do not deadlock.
 * Never logs secret bytes.
 */
object SecretReader {
    private const val EXPECTED_HEX_CHARS = ProtocolV2.SECRET_SIZE * 2
    private const val MAX_LINE_BYTES = EXPECTED_HEX_CHARS + 2
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
        if (length != EXPECTED_HEX_CHARS) {
            throw IllegalArgumentException(
                "Secret on stdin must be a $EXPECTED_HEX_CHARS-char (256-bit) hex string"
            )
        }
        val out = ByteArray(ProtocolV2.SECRET_SIZE)
        for (i in 0 until ProtocolV2.SECRET_SIZE) {
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
