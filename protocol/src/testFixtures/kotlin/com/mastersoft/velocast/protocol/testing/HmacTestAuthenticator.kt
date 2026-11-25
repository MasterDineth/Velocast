package com.mastersoft.velocast.protocol.testing

import com.mastersoft.velocast.protocol.Authenticator
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Test double for [Authenticator]: real HMAC-SHA256 with a fixed key from the JDK, compared in
 * constant time. For tests only; the production authenticator (key derivation from the pairing
 * or session secret) is built in step S06.
 */
class HmacTestAuthenticator(key: ByteArray) : Authenticator {
    private val keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)

    override fun computeProof(transcript: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(transcript)
    }

    override fun verifyProof(transcript: ByteArray, proof: ByteArray): Boolean =
        MessageDigest.isEqual(computeProof(transcript), proof)

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
