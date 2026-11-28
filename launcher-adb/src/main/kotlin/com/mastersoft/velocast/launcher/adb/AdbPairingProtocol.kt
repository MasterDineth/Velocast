package com.mastersoft.velocast.launcher.adb

import com.mastersoft.velocast.protocol.HkdfSha256
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/** Result of a successful Wireless Debugging pairing handshake (`_adb-tls-pairing._tcp`). */
data class AdbPairingResult(val host: String, val port: Int, val peerGuid: String? = null)

/**
 * Implements AOSP's Wireless Debugging Pairing wire protocol (`PairingConnectionCtx` +
 * `PairingAuthCtx` in `packages/modules/adb/pairing_connection/`):
 * 1. TLS 1.3 handshake with self-signed RSA-2048 certificate.
 * 2. RFC 5705 keying-material export (`"adb-label\u0000"`, 64 bytes) concatenated to pairing code.
 * 3. SPAKE2 (`"adb pair client\u0000"`, `"adb pair server\u0000"`) -> HKDF-SHA256 -> AES-128-GCM.
 * 4. Encrypted `PeerInfo` (`8192` bytes) exchange carrying our authorized ADB RSA public key.
 */
internal object AdbPairingProtocol {
    const val HEADER_VERSION: Byte = 1
    const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
    const val PACKET_TYPE_PEER_INFO: Byte = 1
    const val PACKET_HEADER_BYTES = 6
    const val PEER_INFO_BYTES = 8_192
    const val MAX_PACKET_PAYLOAD_BYTES = PEER_INFO_BYTES * 2
    const val PEER_INFO_TYPE_RSA_PUB_KEY: Byte = 0
    const val PEER_INFO_TYPE_DEVICE_GUID: Byte = 0

    private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
    private const val AES_KEY_BYTES = 16
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun pairAsClient(
        host: String,
        port: Int,
        pairingCode: String,
        identity: AdbKeyIdentity,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): AdbPairingResult {
        require(pairingCode.isNotEmpty()) { "Pairing code must not be empty" }
        val plainSocket = Socket()
        plainSocket.soTimeout = readTimeoutMs
        plainSocket.tcpNoDelay = true
        plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val sslSocket = AdbTlsSupport.upgradeToClientTlsSocket(plainSocket, host, port, identity)
        return sslSocket.use { tls ->
            val peerBytes = executeHandshake(
                sslSocket = tls,
                role = Spake2Role.ALICE,
                pairingSecretUtf8 = pairingCode.toByteArray(Charsets.UTF_8),
                ourPeerInfoType = PEER_INFO_TYPE_RSA_PUB_KEY,
                ourPeerInfoPayload = identity.encodedAuthorizedKey()
            )
            AdbPairingResult(
                host = host,
                port = port,
                peerGuid = extractNullTerminatedAscii(peerBytes)
            )
        }
    }

    /**
     * Server-side (`Bob`) pairing handshake used by loopback unit tests (`FakeAdbPairingServer`)
     * to verify full interoperability over a real Conscrypt TLS 1.3 connection.
     */
    internal fun acceptAsServer(
        sslSocket: SSLSocket,
        expectedPairingCode: String,
        deviceGuid: String = "adb-velocast-test-guid"
    ): ByteArray = sslSocket.use { tls ->
        executeHandshake(
            sslSocket = tls,
            role = Spake2Role.BOB,
            pairingSecretUtf8 = expectedPairingCode.toByteArray(Charsets.UTF_8),
            ourPeerInfoType = PEER_INFO_TYPE_DEVICE_GUID,
            ourPeerInfoPayload = "$deviceGuid\u0000".toByteArray(Charsets.UTF_8)
        )
    }

    private fun executeHandshake(
        sslSocket: SSLSocket,
        role: Spake2Role,
        pairingSecretUtf8: ByteArray,
        ourPeerInfoType: Byte,
        ourPeerInfoPayload: ByteArray
    ): ByteArray {
        val exported = AdbTlsSupport.exportKeyingMaterial(sslSocket)
        val combinedPassword = ByteArray(pairingSecretUtf8.size + exported.size)
        pairingSecretUtf8.copyInto(combinedPassword, 0)
        exported.copyInto(combinedPassword, pairingSecretUtf8.size)
        pairingSecretUtf8.fill(0)
        exported.fill(0)

        val myName = if (role == Spake2Role.ALICE) CLIENT_NAME else SERVER_NAME
        val theirName = if (role == Spake2Role.ALICE) SERVER_NAME else CLIENT_NAME
        val input = DataInputStream(sslSocket.inputStream)
        val output = DataOutputStream(sslSocket.outputStream)

        return Spake25519Context(role, myName, theirName).use { spake ->
            val ourSpakeMsg = try {
                spake.generateMessage(combinedPassword)
            } finally {
                combinedPassword.fill(0)
            }
            writePacket(output, PACKET_TYPE_SPAKE2_MSG, ourSpakeMsg)
            val theirSpakeMsg = readPacket(input, PACKET_TYPE_SPAKE2_MSG)
            val rawSharedKey = spake.processMessage(theirSpakeMsg)
                ?: throw GeneralSecurityException("SPAKE2 peer point validation failed")

            PairingSessionCipher.fromSpakeKey(rawSharedKey).use { cipher ->
                rawSharedKey.fill(0)
                val ourPeerInfo = buildPeerInfo(ourPeerInfoType, ourPeerInfoPayload)
                val encryptedPeerInfo = cipher.encrypt(ourPeerInfo)
                writePacket(output, PACKET_TYPE_PEER_INFO, encryptedPeerInfo)

                val theirEncryptedPeerInfo = readPacket(input, PACKET_TYPE_PEER_INFO)
                val theirPlainPeerInfo = cipher.decrypt(theirEncryptedPeerInfo)
                if (theirPlainPeerInfo.size != PEER_INFO_BYTES) {
                    throw IOException("Unexpected PeerInfo size: ${theirPlainPeerInfo.size}")
                }
                theirPlainPeerInfo.copyOfRange(1, PEER_INFO_BYTES)
            }
        }
    }

    private fun buildPeerInfo(type: Byte, data: ByteArray): ByteArray {
        val out = ByteArray(PEER_INFO_BYTES)
        out[0] = type
        data.copyInto(out, 1, 0, data.size.coerceAtMost(PEER_INFO_BYTES - 1))
        return out
    }

    private fun writePacket(output: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(PACKET_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray {
        val headerBytes = ByteArray(PACKET_HEADER_BYTES)
        input.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val size = header.int
        val validHeader = version == HEADER_VERSION &&
            type == expectedType &&
            size in 1..MAX_PACKET_PAYLOAD_BYTES
        if (!validHeader) {
            throw IOException(
                "Invalid pairing packet header (version=$version, type=$type, size=$size)"
            )
        }
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun extractNullTerminatedAscii(data: ByteArray): String? {
        val end = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
        return if (end == 0) null else String(data, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
    }

    private class PairingSessionCipher private constructor(keyBytes: ByteArray) : Closeable {
        private val rawKey = keyBytes.copyOf()
        private val secretKey = SecretKeySpec(rawKey, "AES")
        private var encCounter = 0L
        private var decCounter = 0L

        fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_BITS, nextIv(encCounter++))
            )
            return cipher.doFinal(plaintext)
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_BITS, nextIv(decCounter++))
            )
            return cipher.doFinal(ciphertext)
        }

        override fun close() {
            rawKey.fill(0)
        }

        private fun nextIv(counter: Long): ByteArray = ByteBuffer.allocate(GCM_IV_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(counter)
            .array()

        companion object {
            fun fromSpakeKey(spakeSharedKey: ByteArray): PairingSessionCipher {
                val derived = HkdfSha256.deriveKey(
                    ikm = spakeSharedKey,
                    salt = ByteArray(0),
                    info = HKDF_INFO,
                    length = AES_KEY_BYTES
                )
                return try {
                    PairingSessionCipher(derived)
                } finally {
                    derived.fill(0)
                }
            }
        }
    }
}
