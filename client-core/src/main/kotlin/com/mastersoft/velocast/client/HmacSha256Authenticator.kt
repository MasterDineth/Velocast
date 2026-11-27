package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.ProtocolV2
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 [Authenticator] for protocol v2 sessions until S06 replaces this with HKDF-derived
 * keys. Compares proofs in constant time and omits key material from [toString].
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
        private const val ALGORITHM = "HmacSHA256"
    }
}
