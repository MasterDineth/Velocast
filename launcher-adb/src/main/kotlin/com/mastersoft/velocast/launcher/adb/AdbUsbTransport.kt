package com.mastersoft.velocast.launcher.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claimed USB ADB interface exposing packet-level bulk IN and bulk OUT transfers (`S07C` #1).
 */
interface UsbBulkConnection : Closeable {
    val descriptor: AdbUsbDeviceDescriptor
    val inMaxPacketSize: Int get() = descriptor.inMaxPacketSize
    val outMaxPacketSize: Int get() = descriptor.outMaxPacketSize
    val isClosed: Boolean

    /**
     * Reads up to [length] bytes from the ADB bulk IN endpoint into `buffer[offset .. offset+length)`.
     *
     * @return number of bytes transferred (`0` denotes a USB Zero-Length Packet), or `< 0` on
     *   unmapped transport failure. Implementations throw [SocketTimeoutException] when a positive
     *   [timeoutMs] expires while the USB device remains attached, and [EOFException] on detach.
     */
    @Throws(IOException::class)
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /**
     * Writes `buffer[offset .. offset+length)` to the ADB bulk OUT endpoint (`length == 0` sends a
     * USB Zero-Length Packet).
     *
     * @return number of bytes transferred, or `< 0` on unmapped failure.
     */
    @Throws(IOException::class)
    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
}

/**
 * Packet-preserving ADB USB OTG transport (`S07C` #1, `docs/adb-library-decision.md`).
 *
 * Enforces `adbd`'s FunctionFS USB framing rules:
 * 1. Every 24-byte [AdbPacket] header and non-empty payload are sent as distinct USB bulk OUT
 *    transfers (never coalesced into a single transfer).
 * 2. Large payloads are split into [maxBulkTransferBytes] (`16 KB`) chunks aligned to USB bulk
 *    `wMaxPacketSize`, avoiding Android 8.0–8.1 `usbfs` truncation while preserving packet framing.
 * 3. Any non-empty payload whose byte length is an exact multiple of `outMaxPacketSize` is
 *    followed by a 0-byte USB Zero-Length Packet (ZLP) so `adbd` terminates its bulk read cleanly.
 * 4. Leading 0-byte ZLPs emitted by `adbd` after `inMaxPacketSize`-aligned inbound payloads are
 *    transparently skipped before decoding the next 24-byte header.
 * 5. Multiplexed reads poll with a bounded [multiplexPollTimeoutMs] slice rather than blocking
 *    indefinitely inside `ioctl(USBDEVFS_BULK, timeout=0)`, guaranteeing clean shutdown on detach.
 */
class AdbUsbTransport(
    private val connection: UsbBulkConnection,
    handshakeReadTimeoutMs: Int = AdbClientConfig.DEFAULT_HANDSHAKE_READ_TIMEOUT_MS,
    private val writeTimeoutMs: Int = DEFAULT_USB_WRITE_TIMEOUT_MS,
    private val multiplexPollTimeoutMs: Int = DEFAULT_MULTIPLEX_POLL_TIMEOUT_MS,
    private val maxBulkTransferBytes: Int = AdbUsbConstants.DEFAULT_MAX_BULK_TRANSFER_BYTES
) : AdbPacketTransport {
    init {
        require(handshakeReadTimeoutMs >= 0) {
            "handshakeReadTimeoutMs must be >= 0, got $handshakeReadTimeoutMs"
        }
        require(writeTimeoutMs > 0) { "writeTimeoutMs must be > 0, got $writeTimeoutMs" }
        require(multiplexPollTimeoutMs > 0) {
            "multiplexPollTimeoutMs must be > 0, got $multiplexPollTimeoutMs"
        }
        require(maxBulkTransferBytes >= AdbPacket.HEADER_BYTES) {
            "maxBulkTransferBytes must be >= ${AdbPacket.HEADER_BYTES}, got $maxBulkTransferBytes"
        }
    }

    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var readTimeoutMs: Int = handshakeReadTimeoutMs

    @Volatile
    private var peerSkipsChecksum: Boolean = false

    val descriptor: AdbUsbDeviceDescriptor
        get() = connection.descriptor

    override fun setReadTimeout(timeoutMs: Int) {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0, got $timeoutMs" }
        readTimeoutMs = timeoutMs
    }

    override fun clearReadTimeoutForMultiplexing() {
        readTimeoutMs = 0
    }

    override fun readPacket(): AdbPacket {
        ensureOpen()
        val headerBytes = readHeaderBytes()
        val header = AdbPacket.decodeHeader(headerBytes)
        if (header.command == AdbPacket.A_CNXN &&
            header.arg0.toUInt() >= AdbPacket.A_VERSION_SKIP_CHECKSUM.toUInt()
        ) {
            peerSkipsChecksum = true
        }
        val payload = if (header.payloadLength == 0) {
            AdbPacket.EMPTY_BYTES
        } else {
            readPayloadBytes(header.payloadLength)
        }
        verifyChecksumIfRequired(header, payload)
        return AdbPacket(header.command, header.arg0, header.arg1, payload)
    }

    override fun writePacket(packet: AdbPacket, skipChecksum: Boolean) {
        ensureOpen()
        synchronized(writeLock) {
            ensureOpen()
            val headerBytes = packet.encodeHeader(skipChecksum)
            UsbBulkTransferIo.writeChunked(
                connection = connection,
                bytes = headerBytes,
                maxChunkBytes = AdbPacket.HEADER_BYTES,
                timeoutMs = writeTimeoutMs
            )
            if (packet.payload.isNotEmpty()) {
                UsbBulkTransferIo.writeChunked(
                    connection = connection,
                    bytes = packet.payload,
                    maxChunkBytes = maxBulkTransferBytes,
                    timeoutMs = writeTimeoutMs
                )
                val outMax = connection.outMaxPacketSize
                if (outMax > 0 && packet.payload.size % outMax == 0) {
                    UsbBulkTransferIo.writeZeroLengthPacket(connection, writeTimeoutMs)
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            connection.close()
        }
    }

    private fun readHeaderBytes(): ByteArray = UsbBulkTransferIo.readHeader(
        connection = connection,
        closed = closed,
        readTimeoutProvider = { readTimeoutMs },
        multiplexPollTimeoutMs = multiplexPollTimeoutMs
    )

    private fun readPayloadBytes(payloadLength: Int): ByteArray {
        val effectiveTimeout = readTimeoutMs.takeIf { it > 0 } ?: DEFAULT_PAYLOAD_READ_TIMEOUT_MS
        return UsbBulkTransferIo.readExactPayload(
            connection = connection,
            closed = closed,
            payloadLength = payloadLength,
            maxChunkBytes = maxBulkTransferBytes,
            timeoutMs = effectiveTimeout
        )
    }

    private fun verifyChecksumIfRequired(header: AdbPacket.DecodedHeader, payload: ByteArray) {
        val mustVerify = header.checksum != 0 ||
            (!peerSkipsChecksum && header.command != AdbPacket.A_STLS)
        if (mustVerify) {
            val expected = AdbPacket.computeChecksum(payload)
            if (header.checksum != expected) {
                throw IOException(
                    "Corrupted USB ADB payload checksum: expected $expected, got ${header.checksum}"
                )
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get() || connection.isClosed) {
            throw EOFException("USB ADB transport is closed")
        }
    }

    companion object {
        const val DEFAULT_USB_WRITE_TIMEOUT_MS = 5_000
        const val DEFAULT_MULTIPLEX_POLL_TIMEOUT_MS = 500
        const val DEFAULT_PAYLOAD_READ_TIMEOUT_MS = 10_000

        /**
         * Opens an [AdbUsbTransport] directly from an Android [UsbManager] and permitted
         * [UsbDevice] exposing the ADB bulk interface (`0xFF / 0x42 / 0x01`).
         */
        @Throws(IOException::class)
        fun open(
            usbManager: UsbManager,
            usbDevice: UsbDevice,
            handshakeReadTimeoutMs: Int = AdbClientConfig.DEFAULT_HANDSHAKE_READ_TIMEOUT_MS,
            writeTimeoutMs: Int = DEFAULT_USB_WRITE_TIMEOUT_MS
        ): AdbUsbTransport {
            val bulkConnection = AndroidUsbConnectionOpener.open(usbManager, usbDevice)
            return AdbUsbTransport(
                connection = bulkConnection,
                handshakeReadTimeoutMs = handshakeReadTimeoutMs,
                writeTimeoutMs = writeTimeoutMs
            )
        }
    }
}

private object UsbBulkTransferIo {
    private const val MAX_LEADING_ZLPS = 2

    fun readHeader(
        connection: UsbBulkConnection,
        closed: AtomicBoolean,
        readTimeoutProvider: () -> Int,
        multiplexPollTimeoutMs: Int
    ): ByteArray {
        val header = ByteArray(AdbPacket.HEADER_BYTES)
        var offset = 0
        var leadingZlps = 0
        while (offset < AdbPacket.HEADER_BYTES) {
            checkOpen(connection, closed)
            val configuredTimeout = readTimeoutProvider()
            val pollMode = configuredTimeout == 0 && offset == 0
            val callTimeout = if (pollMode) {
                multiplexPollTimeoutMs
            } else {
                configuredTimeout.coerceAtLeast(multiplexPollTimeoutMs)
            }
            val transferred = try {
                connection.bulkIn(header, offset, AdbPacket.HEADER_BYTES - offset, callTimeout)
            } catch (e: SocketTimeoutException) {
                if (pollMode && !closed.get() && !connection.isClosed) {
                    leadingZlps = 0
                    continue
                }
                throw e
            }
            offset = advanceHeaderOffset(offset, transferred, leadingZlps, pollMode).also {
                if (transferred == 0 && offset == 0) leadingZlps++
            }
        }
        return header
    }

    private fun advanceHeaderOffset(
        currentOffset: Int,
        transferred: Int,
        leadingZlps: Int,
        pollMode: Boolean
    ): Int = when {
        transferred < 0 -> throw EOFException("USB bulk IN header read failed ($transferred)")

        transferred == 0 && currentOffset == 0 -> {
            if (!pollMode && leadingZlps >= MAX_LEADING_ZLPS) {
                throw EOFException("Excessive consecutive 0-byte USB packets before ADB header")
            }
            0
        }

        transferred == 0 -> throw EOFException(
            "Truncated USB ADB header: expected ${AdbPacket.HEADER_BYTES} bytes, got $currentOffset"
        )

        else -> currentOffset + transferred
    }

    fun readExactPayload(
        connection: UsbBulkConnection,
        closed: AtomicBoolean,
        payloadLength: Int,
        maxChunkBytes: Int,
        timeoutMs: Int
    ): ByteArray {
        val payload = ByteArray(payloadLength)
        var offset = 0
        while (offset < payloadLength) {
            checkOpen(connection, closed)
            val toRead = minOf(maxChunkBytes, payloadLength - offset)
            val transferred = connection.bulkIn(payload, offset, toRead, timeoutMs)
            if (transferred <= 0) {
                throw EOFException(
                    "Truncated USB ADB payload: expected $payloadLength bytes, " +
                        "got $offset (lastRead=$transferred)"
                )
            }
            offset += transferred
        }
        return payload
    }

    fun writeChunked(
        connection: UsbBulkConnection,
        bytes: ByteArray,
        maxChunkBytes: Int,
        timeoutMs: Int
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val toWrite = minOf(maxChunkBytes, bytes.size - offset)
            val written = connection.bulkOut(bytes, offset, toWrite, timeoutMs)
            if (written <= 0) {
                throw IOException(
                    "USB bulk OUT failed at $offset/${bytes.size} (result=$written)"
                )
            }
            offset += written
        }
    }

    fun writeZeroLengthPacket(connection: UsbBulkConnection, timeoutMs: Int) {
        val written = connection.bulkOut(AdbPacket.EMPTY_BYTES, 0, 0, timeoutMs)
        if (written < 0) {
            throw IOException("Failed sending trailing USB zero-length packet ($written)")
        }
    }

    private fun checkOpen(connection: UsbBulkConnection, closed: AtomicBoolean) {
        if (closed.get() || connection.isClosed) {
            throw EOFException("USB ADB transport closed")
        }
    }
}
