package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.protocol.SecretRedactor
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Extends `:protocol`'s [SecretRedactor] with literal string scrubbing for ephemeral pairing codes
 * and QR passwords (`AGENTS.md` Rule 4: never log secrets or PINs).
 */
class AdbSecretScrubber(private val delegate: SecretRedactor = SecretRedactor()) {
    private val literalSecrets = CopyOnWriteArraySet<String>()

    fun registerLiteral(secret: String) {
        if (secret.length >= MIN_LITERAL_LENGTH) {
            literalSecrets.add(secret)
        }
    }

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var scrubbed = text
        for (literal in literalSecrets) {
            if (literal.isNotEmpty() && scrubbed.contains(literal)) {
                scrubbed = scrubbed.replace(literal, SecretRedactor.REDACTED_MARKER)
            }
        }
        scrubbed = PAIRING_CODE_LABEL_REGEX.replace(scrubbed) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SecretRedactor.REDACTED_MARKER}"
        }
        return delegate.redact(scrubbed)
    }

    companion object {
        private const val MIN_LITERAL_LENGTH = 6
        private val PAIRING_CODE_LABEL_REGEX = Regex(
            "(?i)\\b(pairing[_-]?code|qr[_-]?password)(\\s*[:=]\\s*)(?!<redacted>)([^\\s,;)\"']+)"
        )
        val defaultInstance = AdbSecretScrubber()
    }
}
