package com.mastersoft.velocast.launcher.adb

import io.github.andreypfau.curve25519.edwards.CompressedEdwardsY
import io.github.andreypfau.curve25519.edwards.EdwardsPoint
import io.github.andreypfau.curve25519.exceptioin.InvalidYCoordinateException
import io.github.andreypfau.curve25519.scalar.Scalar
import java.io.Closeable
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/** Role in BoringSSL's symmetric SPAKE2 key exchange (`spake25519.cc`). */
internal enum class Spake2Role {
    ALICE,
    BOB
}

/**
 * BoringSSL-compatible SPAKE2 over Edwards25519 (`google/boringssl/crypto/curve25519/spake25519.cc`),
 * delegating all elliptic-curve group and scalar operations to `curve25519-kotlin` (`Apache-2.0`).
 */
internal class Spake25519Context(
    private val role: Spake2Role,
    myName: ByteArray,
    theirName: ByteArray,
    private val random: SecureRandom = SecureRandom()
) : Closeable {
    private val myNameCopy = myName.copyOf()
    private val theirNameCopy = theirName.copyOf()
    private val ephemeralScalarBytes = ByteArray(SCALAR_BYTES)
    private val passwordScalarDiv8Bytes = ByteArray(SCALAR_BYTES)
    private val passwordHash = ByteArray(SHA512_BYTES)
    private val myMessage = ByteArray(POINT_BYTES)
    private var messageGenerated = false
    private var finished = false
    private var closed = false

    /**
     * Generates the 32-byte SPAKE2 public message (`P* = 8*s0*B + w*M` for Alice, `+ w*N` for Bob).
     */
    fun generateMessage(password: ByteArray): ByteArray {
        check(!closed && !messageGenerated) { "SPAKE2 generateMessage called in invalid state" }
        val randomWide = ByteArray(SHA512_BYTES).also { random.nextBytes(it) }
        val s0 = try {
            Scalar.fromWideByteArray(randomWide)
        } finally {
            randomWide.fill(0)
        }
        s0.toByteArray(ephemeralScalarBytes)

        val sha512 = MessageDigest.getInstance(SHA512_ALGORITHM)
        val pwDigest = sha512.digest(password)
        pwDigest.copyInto(passwordHash, 0)
        val w0 = try {
            Scalar.fromWideByteArray(pwDigest)
        } finally {
            pwDigest.fill(0)
        }

        // BoringSSL cofactor-clearing adjustment: add multiples of prime subgroup order L so
        // w = w0 + c*L is divisible by 8 (0 <= w < 8*L). Then w * M == (w / 8) * (8 * M) with
        // 0 <= w / 8 < L, allowing exact torsion-free scalar multiplication via M8 / N8.
        val kBytes = computeCofactorClearedDiv8Scalar(w0.toByteArray())
        kBytes.copyInto(passwordScalarDiv8Bytes, 0)
        val kScalar = Scalar.fromByteArray(kBytes)
        kBytes.fill(0)

        val pPoint = EdwardsPoint.mul(BASEPOINT_MUL_8, s0)
        val maskPoint = EdwardsPoint.mul(
            if (role ==
                Spake2Role.ALICE
            ) {
                POINT_M_MUL_8
            } else {
                POINT_N_MUL_8
            },
            kScalar
        )
        val pStar = EdwardsPoint().apply { add(pPoint, maskPoint) }
        CompressedEdwardsY.from(pStar).data.copyInto(myMessage, 0)
        messageGenerated = true
        return myMessage.copyOf()
    }

    /**
     * Processes the peer's 32-byte SPAKE2 message and returns the 64-byte shared transcript digest,
     * or `null` if [theirMessage] is malformed or not a valid curve point.
     */
    fun processMessage(theirMessage: ByteArray): ByteArray? {
        check(!closed && messageGenerated && !finished) {
            "SPAKE2 processMessage called in invalid state"
        }
        finished = true
        val qStar = decodePeerPoint(theirMessage) ?: return null

        val s0 = Scalar.fromByteArray(ephemeralScalarBytes)
        val kScalar = Scalar.fromByteArray(passwordScalarDiv8Bytes)
        val peerMask = EdwardsPoint.mul(
            if (role == Spake2Role.ALICE) POINT_N_MUL_8 else POINT_M_MUL_8,
            kScalar
        )
        val negPeerMask = EdwardsPoint.negate(peerMask)
        val qExt = EdwardsPoint().apply { add(qStar, negPeerMask) }
        val qExtMul8 = EdwardsPoint.mulByCofactor(qExt)
        val dhShared = EdwardsPoint.mul(qExtMul8, s0)
        val dhSharedBytes = CompressedEdwardsY.from(dhShared).data

        return try {
            computeTranscriptKey(theirMessage, dhSharedBytes)
        } finally {
            dhSharedBytes.fill(0)
        }
    }

    override fun close() {
        closed = true
        ephemeralScalarBytes.fill(0)
        passwordScalarDiv8Bytes.fill(0)
        passwordHash.fill(0)
        myMessage.fill(0)
    }

    private fun computeTranscriptKey(theirMessage: ByteArray, dhSharedBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(SHA512_ALGORITHM)
        if (role == Spake2Role.ALICE) {
            updateLengthPrefixed(digest, myNameCopy)
            updateLengthPrefixed(digest, theirNameCopy)
            updateLengthPrefixed(digest, myMessage)
            updateLengthPrefixed(digest, theirMessage)
        } else {
            updateLengthPrefixed(digest, theirNameCopy)
            updateLengthPrefixed(digest, myNameCopy)
            updateLengthPrefixed(digest, theirMessage)
            updateLengthPrefixed(digest, myMessage)
        }
        updateLengthPrefixed(digest, dhSharedBytes)
        updateLengthPrefixed(digest, passwordHash)
        return digest.digest()
    }

    private fun updateLengthPrefixed(digest: MessageDigest, data: ByteArray) {
        val lenBytes = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(data.size.toLong())
            .array()
        digest.update(lenBytes)
        digest.update(data)
    }

    companion object {
        const val POINT_BYTES = 32
        const val SCALAR_BYTES = 32
        const val SHA512_BYTES = 64
        private const val SHA512_ALGORITHM = "SHA-512"
        private const val SIGN_BIT_MASK = 0x80

        // Standard RFC 8032 Ed25519 basepoint B (y = 4/5 mod p, positive x).
        private const val ED25519_BASEPOINT_HEX =
            "5866666666666666666666666666666666666666666666666666666666666666"

        // BoringSSL SPAKE2 generator points M and N (`crypto/curve25519/spake25519.cc`).
        private const val BORINGSSL_M_HEX =
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"
        private const val BORINGSSL_N_HEX =
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"

        // Prime subgroup order L = 2^252 + 27742317777372353535851937790883648493.
        private val SUBGROUP_ORDER_L = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))

        private val BASEPOINT_MUL_8: EdwardsPoint by lazy {
            EdwardsPoint.mulByCofactor(decompressFixedPoint(ED25519_BASEPOINT_HEX))
        }
        private val POINT_M_MUL_8: EdwardsPoint by lazy {
            EdwardsPoint.mulByCofactor(decompressFixedPoint(BORINGSSL_M_HEX))
        }
        private val POINT_N_MUL_8: EdwardsPoint by lazy {
            EdwardsPoint.mulByCofactor(decompressFixedPoint(BORINGSSL_N_HEX))
        }

        private fun computeCofactorClearedDiv8Scalar(w0LittleEndian: ByteArray): ByteArray {
            var w = BigInteger(1, w0LittleEndian.reversedArray())
            var orderMultiple = SUBGROUP_ORDER_L
            if (w.testBit(0)) w = w.add(orderMultiple)
            orderMultiple = orderMultiple.shiftLeft(1)
            if (w.testBit(1)) w = w.add(orderMultiple)
            orderMultiple = orderMultiple.shiftLeft(1)
            if (w.testBit(2)) w = w.add(orderMultiple)

            val dividedBy8 = w.shiftRight(3)
            val bigEndian = dividedBy8.toByteArray()
            val outLe = ByteArray(SCALAR_BYTES)
            var src = bigEndian.size - 1
            var dst = 0
            while (src >= 0 && dst < SCALAR_BYTES) {
                outLe[dst++] = bigEndian[src--]
            }
            return outLe
        }

        private fun decodePeerPoint(bytes: ByteArray): EdwardsPoint? {
            if (bytes.size != POINT_BYTES) return null
            return try {
                val compressed = CompressedEdwardsY(bytes.copyOf())
                val point = EdwardsPoint.from(compressed)
                // Reject non-canonical negative zero x (`x == 0` with sign bit 1), matching BoringSSL.
                val signSet = (bytes[POINT_BYTES - 1].toInt() and SIGN_BIT_MASK) != 0
                if (signSet && point.x.isZero() == 1) null else point
            } catch (_: InvalidYCoordinateException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun decompressFixedPoint(hex: String): EdwardsPoint {
            val bytes = ByteArray(hex.length / 2) { idx ->
                hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
            return EdwardsPoint.from(CompressedEdwardsY(bytes))
        }
    }
}
