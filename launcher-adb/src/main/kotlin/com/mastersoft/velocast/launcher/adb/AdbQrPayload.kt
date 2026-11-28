package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.protocol.ConstantTime
import com.mastersoft.velocast.protocol.SecretRedactor
import java.security.SecureRandom

/**
 * Standard Android 11+ Wireless Debugging QR code payload (`WIFI:T:ADB;S:<service>;P:<password>;;`)
 * scanned by the old phone under **Developer options -> Wireless debugging -> Pair device with QR code**.
 */
class AdbQrPayload(
    val serviceName: String,
    val password: String,
    scrubber: AdbSecretScrubber? = AdbSecretScrubber.defaultInstance
) {
    init {
        require(serviceName.isNotBlank() && serviceName.length <= MAX_SERVICE_NAME_LENGTH) {
            "serviceName must be 1..$MAX_SERVICE_NAME_LENGTH characters"
        }
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) {
            "password must be 1..$MAX_PASSWORD_LENGTH characters"
        }
        require(serviceName.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "serviceName must not contain NUL or newline characters"
        }
        require(password.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "password must not contain NUL or newline characters"
        }
        scrubber?.registerLiteral(password)
    }

    /** Formats this QR payload into the exact AOSP `AdbQrCode` string (`WIFI:T:ADB;S:...;P:...;;`). */
    fun encode(): String =
        "$PREFIX$FIELD_SERVICE${escapeField(serviceName)};$FIELD_PASSWORD${escapeField(password)};;"

    override fun equals(other: Any?): Boolean = other is AdbQrPayload &&
        serviceName == other.serviceName &&
        ConstantTime.equals(
            password.toByteArray(Charsets.UTF_8),
            other.password.toByteArray(Charsets.UTF_8)
        )

    override fun hashCode(): Int = serviceName.hashCode()

    override fun toString(): String =
        "AdbQrPayload(serviceName=$serviceName, password=${SecretRedactor.REDACTED_MARKER})"

    companion object {
        const val DEFAULT_SERVICE_PREFIX = "studio-"
        const val DEFAULT_RANDOM_SERVICE_CHARS = 10
        const val DEFAULT_PASSWORD_CHARS = 12
        const val MAX_SERVICE_NAME_LENGTH = 63
        const val MAX_PASSWORD_LENGTH = 64

        private const val PREFIX = "WIFI:T:ADB;"
        private const val FIELD_TYPE = "T:"
        private const val FIELD_SERVICE = "S:"
        private const val FIELD_PASSWORD = "P:"
        private const val ADB_TYPE = "ADB"
        private const val ESCAPE_CAPACITY_MARGIN = 4
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val ESCAPED_CHARS = charArrayOf('\\', ';', ',', ':', '"')

        fun generate(
            random: SecureRandom = SecureRandom(),
            servicePrefix: String = DEFAULT_SERVICE_PREFIX,
            scrubber: AdbSecretScrubber? = AdbSecretScrubber.defaultInstance
        ): AdbQrPayload {
            val service = servicePrefix + randomToken(random, DEFAULT_RANDOM_SERVICE_CHARS)
            val password = randomToken(random, DEFAULT_PASSWORD_CHARS)
            return AdbQrPayload(serviceName = service, password = password, scrubber = scrubber)
        }

        /**
         * Parses and validates an AOSP `WIFI:T:ADB;S:<service>;P:<password>;;` QR payload string.
         */
        fun parse(
            raw: String,
            scrubber: AdbSecretScrubber? = AdbSecretScrubber.defaultInstance
        ): AdbQrPayload {
            require(raw.startsWith("WIFI:") && raw.endsWith(";;")) {
                "Invalid ADB QR payload framing"
            }
            val body = raw.substring("WIFI:".length, raw.length - 2)
            val tokens = splitUnescapedSemicolons(body)
            var typeValue: String? = null
            var serviceValue: String? = null
            var passwordValue: String? = null
            for (token in tokens) {
                if (token.isEmpty()) continue
                when {
                    token.startsWith(FIELD_TYPE) ->
                        typeValue = unescapeField(token.substring(FIELD_TYPE.length))

                    token.startsWith(FIELD_SERVICE) ->
                        serviceValue = unescapeField(token.substring(FIELD_SERVICE.length))

                    token.startsWith(FIELD_PASSWORD) ->
                        passwordValue = unescapeField(token.substring(FIELD_PASSWORD.length))
                }
            }
            require(typeValue == ADB_TYPE) { "Unsupported WIFI QR type: $typeValue" }
            val resolvedService = requireNotNull(serviceValue) { "Missing S: service field" }
            val resolvedPassword = requireNotNull(passwordValue) { "Missing P: password field" }
            return AdbQrPayload(resolvedService, resolvedPassword, scrubber)
        }

        private fun escapeField(value: String): String =
            buildString(value.length + ESCAPE_CAPACITY_MARGIN) {
                for (ch in value) {
                    if (ESCAPED_CHARS.contains(ch)) append('\\')
                    append(ch)
                }
            }

        private fun unescapeField(value: String): String = buildString(value.length) {
            var idx = 0
            while (idx < value.length) {
                val ch = value[idx]
                if (ch == '\\') {
                    require(idx + 1 < value.length) { "Trailing backslash in QR field" }
                    append(value[idx + 1])
                    idx += 2
                } else {
                    append(ch)
                    idx += 1
                }
            }
        }

        private fun splitUnescapedSemicolons(body: String): List<String> {
            val parts = ArrayList<String>(4)
            val current = StringBuilder()
            var escaped = false
            for (ch in body) {
                if (escaped) {
                    current.append('\\').append(ch)
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == ';') {
                    parts += current.toString()
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
            }
            require(!escaped) { "Trailing escape in QR payload" }
            if (current.isNotEmpty()) {
                parts += current.toString()
            }
            return parts
        }

        private fun randomToken(random: SecureRandom, length: Int): String {
            val chars = CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
            return String(chars)
        }
    }
}
