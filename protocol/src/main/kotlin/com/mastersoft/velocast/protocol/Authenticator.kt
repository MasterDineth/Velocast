package com.mastersoft.velocast.protocol

import java.security.MessageDigest

/** Which side of the handshake produces a proof. Part of the transcript (domain separation). */
enum class AuthRole(internal val label: String) {
    CLIENT("VLC2 client proof"),
    SERVER("VLC2 server proof")
}

/**
 * How a 256-bit shared secret was provisioned for the current session (`docs/security.md`).
 */
enum class AuthMode {
    /** Fresh 256-bit CSPRNG secret per session, passed to the ADB-launched server on `stdin`. */
    SESSION_SECRET,

    /** Persistent 256-bit CSPRNG secret created during `Prepare`, stored in Keystore files. */
    PAIRING_SECRET
}

/**
 * Proves knowledge of the shared secret (`SESSION_SECRET` or `PAIRING_SECRET`) over a handshake
 * transcript and optionally derives per-session AEAD keys once both nonces are known.
 */
interface Authenticator {
    /** True when the session MUST negotiate [Features.SECURE_CHANNEL] and install AEAD framing. */
    val requiresSecureChannel: Boolean
        get() = false

    /** Returns the 32-byte HMAC-SHA256 proof for [transcript]. */
    fun computeProof(transcript: ByteArray): ByteArray

    /** Checks [proof] against [transcript]. Implementations MUST compare in constant time. */
    fun verifyProof(transcript: ByteArray, proof: ByteArray): Boolean

    /**
     * Derives directional session AEAD keys from [clientNonce] and [serverNonce], or returns `null`
     * for unencrypted protocol-only test doubles.
     */
    fun deriveSessionKeys(clientNonce: ByteArray, serverNonce: ByteArray): SessionKeys? = null
}

/**
 * The byte string a proof is computed over: the 17-byte role label, the exact 26-byte HELLO
 * payload, and the exact 22-byte SERVER_HELLO payload (without any frame or packet header).
 * Both sides therefore sign protocol versions, feature/capability bitmaps, and both nonces.
 */
object Transcript {
    const val TOTAL_SIZE =
        ProtocolV2.TRANSCRIPT_LABEL_SIZE + ProtocolV2.HELLO_SIZE + ProtocolV2.SERVER_HELLO_SIZE

    private const val CLIENT_NONCE_OFFSET = ProtocolV2.TRANSCRIPT_LABEL_SIZE + 10
    private const val SERVER_NONCE_OFFSET =
        ProtocolV2.TRANSCRIPT_LABEL_SIZE + ProtocolV2.HELLO_SIZE + 6

    fun build(role: AuthRole, helloPayload: ByteArray, serverHelloPayload: ByteArray): ByteArray {
        val label = role.label.toByteArray(Charsets.US_ASCII)
        return label + helloPayload + serverHelloPayload
    }

    /** Computes the 32-byte `SHA-256` digest over a raw [transcript] built by [build]. */
    fun sha256(transcript: ByteArray): ByteArray =
        MessageDigest.getInstance(SHA256_ALGORITHM).digest(transcript)

    /** Builds and hashes the transcript in one step. */
    fun sha256(role: AuthRole, helloPayload: ByteArray, serverHelloPayload: ByteArray): ByteArray =
        sha256(build(role, helloPayload, serverHelloPayload))

    /** Extracts `(clientNonce, serverNonce)` from a 65-byte transcript produced by [build]. */
    fun extractNonces(transcript: ByteArray): Pair<ByteArray, ByteArray> {
        if (transcript.size != TOTAL_SIZE) {
            fail(
                ErrorCode.MALFORMED_MESSAGE,
                "Invalid handshake transcript length ${transcript.size}, expected $TOTAL_SIZE"
            )
        }
        val clientNonce = transcript.copyOfRange(
            CLIENT_NONCE_OFFSET,
            CLIENT_NONCE_OFFSET + ProtocolV2.NONCE_SIZE
        )
        val serverNonce = transcript.copyOfRange(
            SERVER_NONCE_OFFSET,
            SERVER_NONCE_OFFSET + ProtocolV2.NONCE_SIZE
        )
        return clientNonce to serverNonce
    }

    private const val SHA256_ALGORITHM = "SHA-256"
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

    /** AES-256-GCM record framing on all post-handshake session traffic (`S06`). */
    const val SECURE_CHANNEL = 1 shl 5
}
