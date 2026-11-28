package com.mastersoft.velocast.protocol

import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Scrubs secret material (`SESSION_SECRET`, `PAIRING_SECRET`, derived keys, `AUTH_PROOF`, PINs)
 * from log messages, stack traces, and diagnostics output (`docs/security.md`).
 */
class SecretRedactor {
    private val registeredLiterals = CopyOnWriteArraySet<String>()

    /**
     * Registers raw [secret] bytes so any hex or Base64/Base64URL encoding of [secret] is scrubbed
     * even when logged without a `secret=` key label. Never retains the raw byte array reference.
     */
    fun registerSecret(secret: ByteArray) {
        if (secret.size < MIN_REGISTERED_SECRET_BYTES) return
        val lowerHex = toLowerHex(secret)
        registeredLiterals.add(lowerHex)
        registeredLiterals.add(lowerHex.uppercase(Locale.ROOT))
        registeredLiterals.add(Base64.getEncoder().encodeToString(secret))
        registeredLiterals.add(Base64.getEncoder().withoutPadding().encodeToString(secret))
        registeredLiterals.add(Base64.getUrlEncoder().encodeToString(secret))
        registeredLiterals.add(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
    }

    fun clearRegisteredSecrets() {
        registeredLiterals.clear()
    }

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var scrubbed = text
        for (literal in registeredLiterals) {
            if (literal.isNotEmpty() && scrubbed.contains(literal)) {
                scrubbed = scrubbed.replace(literal, REDACTED_MARKER)
            }
        }
        return scrubCommonPatterns(scrubbed)
    }

    fun redact(throwable: Throwable): String = redact(throwable.stackTraceToString().trimEnd())

    companion object {
        const val REDACTED_MARKER = "<redacted>"
        private const val MIN_REGISTERED_SECRET_BYTES = 8
        private const val BYTE_MASK = 0xFF
        private const val HIGH_NIBBLE_SHIFT = 4
        private const val LOW_NIBBLE_MASK = 0x0F
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        private val LABELED_SECRET_REGEX = Regex(
            "(?i)\\b(" +
                "secret|pairing[_-]?secret|session[_-]?secret|" +
                "auth[_-]?proof|proof|auth[_-]?key|c2s[_-]?key|s2c[_-]?key|" +
                "pin|passcode|password" +
                ")(\\s*[:=]\\s*)(?!<redacted>)([^\\s,;)\"']+)"
        )

        private val LONG_HEX_REGEX = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64,}(?![0-9a-fA-F])")

        private val defaultInstance = SecretRedactor()

        fun redact(text: String): String = defaultInstance.redact(text)

        fun redact(throwable: Throwable): String = defaultInstance.redact(throwable)

        private fun scrubCommonPatterns(input: String): String {
            val afterLabels = LABELED_SECRET_REGEX.replace(input) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_MARKER"
            }
            return LONG_HEX_REGEX.replace(afterLabels, REDACTED_MARKER)
        }

        private fun toLowerHex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val value = bytes[i].toInt() and BYTE_MASK
                chars[i * 2] = HEX_DIGITS[value ushr HIGH_NIBBLE_SHIFT]
                chars[i * 2 + 1] = HEX_DIGITS[value and LOW_NIBBLE_MASK]
            }
            return String(chars)
        }
    }
}
