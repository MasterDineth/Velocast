package com.mastersoft.velocast.launcher.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Encodes and decodes AOSP's 524-byte binary RSA-2048 public key format (`libcrypto_utils`
 * `android_pubkey_encode`) used in ADB `PeerInfo` (`_adb-tls-pairing._tcp`) and `ADB_AUTH_RSAPUBLICKEY`,
 * plus raw PKCS#1 v1.5 SHA-1 DigestInfo signing for `ADB_AUTH_SIGNATURE`.
 */
internal object AndroidPubkeyCodec {
    const val MODULUS_BITS = 2048
    const val MODULUS_BYTES = MODULUS_BITS / 8
    const val MODULUS_WORDS = MODULUS_BYTES / 4
    const val ANDROID_PUBKEY_STRUCT_BYTES = 3 * Int.SIZE_BYTES + 2 * MODULUS_BYTES // 524 bytes
    const val DEFAULT_OWNER_NAME = "velocast@viewer"

    // Pre-hashed SHA-1 DigestInfo ASN.1 prefix expected by adbd's `ADB_AUTH_SIGNATURE` verifier.
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    )
    private const val SHA1_TOKEN_BYTES = 20
    private val BIG_INT_TWO = BigInteger.valueOf(2L)

    /**
     * Encodes [publicKey] into the Base64 `<struct> <ownerName>\0` format expected by `adbd`
     * (`/data/misc/adb/adb_keys` and `PeerInfo.ADB_RSA_PUB_KEY`).
     */
    fun encodeAuthorizedKey(
        publicKey: RSAPublicKey,
        ownerName: String = DEFAULT_OWNER_NAME
    ): ByteArray {
        val binary = encodeBinaryStruct(publicKey)
        val base64 = Base64.getEncoder().encodeToString(binary)
        return "$base64 $ownerName\u0000".toByteArray(Charsets.UTF_8)
    }

    fun encodeBinaryStruct(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        if (modulus.bitLength() != MODULUS_BITS) {
            throw InvalidKeyException(
                "ADB RSA key must be $MODULUS_BITS bits, got ${modulus.bitLength()}"
            )
        }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.mod(r32)
        val n0Inv = r32.subtract(n0.modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS).modPow(BIG_INT_TWO, modulus)

        val buffer = ByteBuffer.allocate(ANDROID_PUBKEY_STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0Inv.toInt())
        buffer.put(toLittleEndianFixed(modulus, MODULUS_BYTES))
        buffer.put(toLittleEndianFixed(rr, MODULUS_BYTES))
        buffer.putInt(publicKey.publicExponent.toInt())
        return buffer.array()
    }

    fun decodeBinaryStruct(structBytes: ByteArray): RSAPublicKey {
        if (structBytes.size < ANDROID_PUBKEY_STRUCT_BYTES) {
            throw InvalidKeyException("Invalid Android pubkey length: ${structBytes.size}")
        }
        val buffer = ByteBuffer.wrap(structBytes).order(ByteOrder.LITTLE_ENDIAN)
        val words = buffer.int
        if (words != MODULUS_WORDS) {
            throw InvalidKeyException("Unsupported Android pubkey modulus words: $words")
        }
        buffer.int // skip n0inv
        val modulusLe = ByteArray(MODULUS_BYTES)
        buffer.get(modulusLe)
        val rrSkip = ByteArray(MODULUS_BYTES)
        buffer.get(rrSkip)
        val exponent = buffer.int.toLong() and 0xFFFF_FFFFL

        val modulusBe = modulusLe.reversedArray()
        val modulus = BigInteger(1, modulusBe)
        val spec = RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent))
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    /**
     * Signs a 20-byte `ADB_AUTH_TOKEN` payload using PKCS#1 v1.5 type-1 padding over the raw token
     * (matching AOSP `adb_auth_sign`, where the 20-byte token is treated as an already-computed SHA-1 digest).
     */
    fun signAdbAuthToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        require(token.size == SHA1_TOKEN_BYTES) {
            "ADB auth token must be $SHA1_TOKEN_BYTES bytes, got ${token.size}"
        }
        val digestInfo = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token.size)
        SHA1_DIGEST_INFO_PREFIX.copyInto(digestInfo, 0)
        token.copyInto(digestInfo, SHA1_DIGEST_INFO_PREFIX.size)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(digestInfo)
    }

    private fun toLittleEndianFixed(value: BigInteger, length: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(length)
        var srcIdx = bigEndian.size - 1
        var dstIdx = 0
        while (srcIdx >= 0 && dstIdx < length) {
            out[dstIdx++] = bigEndian[srcIdx--]
        }
        return out
    }
}
