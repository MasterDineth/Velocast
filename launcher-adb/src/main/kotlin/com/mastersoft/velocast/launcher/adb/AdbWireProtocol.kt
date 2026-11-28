package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * 24-byte little-endian ADB transport packet (`packages/modules/adb/protocol.txt`).
 */
class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY_BYTES
) {
    fun encodeHeader(skipChecksum: Boolean): ByteArray {
        val checksum = if (skipChecksum) 0 else computeChecksum(payload)
        return ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(checksum)
            .putInt(command xor -1)
            .array()
    }

    override fun toString(): String =
        "AdbPacket(cmd=${commandName(command)}, arg0=$arg0, arg1=$arg1, len=${payload.size})"

    companion object {
        const val HEADER_BYTES = 24
        const val MAX_PACKET_PAYLOAD_BYTES = 1024 * 1024

        const val A_SYNC = 0x434E5953
        const val A_CNXN = 0x4E584E43
        const val A_OPEN = 0x4E45504F
        const val A_OKAY = 0x59414B4F
        const val A_CLSE = 0x45534C43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534C5453

        const val A_VERSION_MIN = 0x01000000
        const val A_VERSION_SKIP_CHECKSUM = 0x01000001
        const val A_STLS_VERSION = 0x01000000

        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3

        const val DEFAULT_MAX_DATA = 1024 * 1024
        const val STREAM_WRITE_CHUNK_CAP = 64 * 1024
        private const val BYTE_MASK = 0xFF
        private const val HEX_RADIX = 16

        val EMPTY_BYTES = ByteArray(0)
        val HOST_CONNECT_BANNER: ByteArray =
            (
                "host::features=shell_v2,cmd,stat_v2,abb_exec," +
                    "fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp\u0000"
                ).toByteArray(Charsets.UTF_8)

        fun computeChecksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) {
                sum += b.toInt() and BYTE_MASK
            }
            return sum
        }

        fun decodeHeader(headerBytes: ByteArray): DecodedHeader {
            require(headerBytes.size == HEADER_BYTES) {
                "ADB header must be $HEADER_BYTES bytes, got ${headerBytes.size}"
            }
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            val checksum = buf.int
            val magic = buf.int
            if (magic != (command xor -1)) {
                throw IOException(
                    "Corrupted ADB packet magic for command 0x${command.toUInt().toString(
                        HEX_RADIX
                    )}"
                )
            }
            if (length < 0 || length > MAX_PACKET_PAYLOAD_BYTES) {
                throw IOException("ADB packet length out of bounds: $length")
            }
            return DecodedHeader(command, arg0, arg1, length, checksum)
        }

        fun commandName(command: Int): String = when (command) {
            A_SYNC -> "SYNC"
            A_CNXN -> "CNXN"
            A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"
            A_WRTE -> "WRTE"
            A_AUTH -> "AUTH"
            A_STLS -> "STLS"
            else -> "0x${command.toUInt().toString(HEX_RADIX)}"
        }
    }

    data class DecodedHeader(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payloadLength: Int,
        val checksum: Int
    )
}

/**
 * Packet-preserving transport underlying [AdbConnectionSession].
 *
 * Keeping `readPacket` / `writePacket` packet-oriented allows TCP/TLS sockets (`S07A`) and USB OTG
 * bulk transfers (`S07C`) to share the exact same connection handshake and stream multiplexer.
 */
interface AdbPacketTransport : Closeable {
    @Throws(IOException::class)
    fun readPacket(): AdbPacket

    @Throws(IOException::class)
    fun writePacket(packet: AdbPacket, skipChecksum: Boolean)

    fun setReadTimeout(timeoutMs: Int) = Unit

    fun clearReadTimeoutForMultiplexing() = Unit

    fun upgradeToTls(identity: AdbKeyIdentity): Unit =
        throw UnsupportedOperationException("TLS upgrade is not supported on this ADB transport")
}

/**
 * TCP/TLS [AdbPacketTransport] supporting in-place `A_STLS` upgrade to Conscrypt `TLSv1.3`.
 */
internal class SocketAdbPacketTransport(
    private val host: String,
    private val port: Int,
    connectTimeoutMs: Int,
    handshakeReadTimeoutMs: Int
) : AdbPacketTransport {
    private val writeLock = Any()
    private val plainSocket = Socket()

    @Volatile
    private var tlsSocket: SSLSocket? = null

    @Volatile
    private var input: DataInputStream

    @Volatile
    private var output: DataOutputStream

    init {
        plainSocket.tcpNoDelay = true
        plainSocket.soTimeout = handshakeReadTimeoutMs
        plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        input = DataInputStream(plainSocket.getInputStream())
        output = DataOutputStream(plainSocket.getOutputStream())
    }

    override fun readPacket(): AdbPacket {
        val currentInput = input
        val headerBytes = ByteArray(AdbPacket.HEADER_BYTES)
        currentInput.readFully(headerBytes)
        val header = AdbPacket.decodeHeader(headerBytes)
        val payload = if (header.payloadLength == 0) {
            AdbPacket.EMPTY_BYTES
        } else {
            ByteArray(header.payloadLength).also { currentInput.readFully(it) }
        }
        return AdbPacket(header.command, header.arg0, header.arg1, payload)
    }

    override fun writePacket(packet: AdbPacket, skipChecksum: Boolean) {
        synchronized(writeLock) {
            val currentOutput = output
            currentOutput.write(packet.encodeHeader(skipChecksum))
            if (packet.payload.isNotEmpty()) {
                currentOutput.write(packet.payload)
            }
            currentOutput.flush()
        }
    }

    override fun upgradeToTls(identity: AdbKeyIdentity) {
        synchronized(writeLock) {
            val upgraded = AdbTlsSupport.upgradeToClientTlsSocket(plainSocket, host, port, identity)
            tlsSocket = upgraded
            input = DataInputStream(upgraded.inputStream)
            output = DataOutputStream(upgraded.outputStream)
        }
    }

    override fun setReadTimeout(timeoutMs: Int) {
        plainSocket.soTimeout = timeoutMs
        tlsSocket?.soTimeout = timeoutMs
    }

    override fun clearReadTimeoutForMultiplexing() {
        setReadTimeout(0)
    }

    override fun close() {
        try {
            tlsSocket?.close()
        } catch (_: IOException) {
        }
        try {
            plainSocket.close()
        } catch (_: IOException) {
        }
    }
}
