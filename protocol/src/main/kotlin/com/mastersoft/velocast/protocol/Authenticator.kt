package com.mastersoft.velocast.protocol

/** Which side of the handshake produces a proof. Part of the transcript (domain separation). */
enum class AuthRole(internal val label: String) {
    CLIENT("VLC2 client proof"),
    SERVER("VLC2 server proof")
}

/**
 * Proves knowledge of the shared secret (session or pairing secret) over a handshake transcript.
 * The production implementation (HMAC-SHA256 with a derived key) arrives in step S06; this module
 * only defines the contract. See `HmacTestAuthenticator` in the test fixtures for a test double.
 */
interface Authenticator {
    /** Returns the 32-byte HMAC-SHA256 of [transcript]. */
    fun computeProof(transcript: ByteArray): ByteArray

    /** Checks [proof] against [transcript]. Implementations MUST compare in constant time. */
    fun verifyProof(transcript: ByteArray, proof: ByteArray): Boolean
}

/**
 * The byte string a proof is computed over: the role label, the exact HELLO payload and the
 * exact SERVER_HELLO payload (without any frame or packet header). Both sides therefore sign
 * both nonces and everything that was negotiated.
 */
object Transcript {
    fun build(role: AuthRole, helloPayload: ByteArray, serverHelloPayload: ByteArray): ByteArray {
        val label = role.label.toByteArray(Charsets.US_ASCII)
        return label + helloPayload + serverHelloPayload
    }
}

/**
 * Optional feature bits, used in HELLO.features (wanted) and SERVER_HELLO.capabilities (offered).
 */
object Features {
    const val HEVC = 1 shl 0
    const val CLIPBOARD = 1 shl 1
    const val TEXT_INPUT = 1 shl 2
    const val DISPLAY_POWER = 1 shl 3
    const val ROTATION_EVENTS = 1 shl 4

    /** Reserved for the encrypted record layer of step S06. */
    const val SECURE_CHANNEL = 1 shl 5
}
