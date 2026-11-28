package com.mastersoft.velocast.launcher.adb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.withLock

/** Metadata negotiated from `adbd`'s `A_CNXN` banner upon connection. */
data class AdbConnectedDevice(
    val host: String,
    val port: Int,
    val banner: String,
    val productName: String? = null,
    val model: String? = null,
    val device: String? = null,
    val features: Set<String> = emptySet(),
    val isTls: Boolean = false,
    val maxData: Int = AdbPacket.DEFAULT_MAX_DATA
)

/**
 * Bidirectional stream opened over an authenticated ADB connection (`openStream("shell:...")`,
 * `openStream("sync:")`, `openStream("tcp:27183")`, etc.).
 */
interface AdbByteStream : Closeable {
    val service: String
    val inputStream: InputStream
    val outputStream: OutputStream
    val isClosed: Boolean

    fun setReadTimeout(timeoutMs: Int) = Unit
}

/**
 * Multiplexes ADB streams (`OPEN`/`OKAY`/`WRTE`/`CLSE`) over a connected [AdbPacketTransport].
 */
internal class AdbConnectionSession private constructor(
    private val transport: AdbPacketTransport,
    val connectedDevice: AdbConnectedDevice,
    private val skipChecksum: Boolean,
    private val streamIoTimeoutMs: Long
) : Closeable {
    private val nextLocalId = AtomicInteger(0)
    private val streams = ConcurrentHashMap<Int, MultiplexedAdbStream>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var readerFailure: IOException? = null

    private val demuxThread = Thread(::runDemuxLoop, "velocast-adb-demux").apply {
        isDaemon = true
    }

    private fun startDemux() {
        transport.clearReadTimeoutForMultiplexing()
        demuxThread.start()
    }

    fun openStream(service: String): AdbByteStream {
        require(service.isNotBlank()) { "ADB service string must not be blank" }
        ensureOpen()
        val localId = nextLocalId.incrementAndGet()
        val maxChunk = connectedDevice.maxData
            .coerceAtLeast(MIN_MAX_DATA)
            .coerceAtMost(AdbPacket.STREAM_WRITE_CHUNK_CAP)
        val stream = MultiplexedAdbStream(
            session = this,
            service = service,
            localId = localId,
            maxWriteChunk = maxChunk,
            ioTimeoutMs = streamIoTimeoutMs
        )
        streams[localId] = stream
        try {
            val payload = "$service\u0000".toByteArray(Charsets.UTF_8)
            sendPacket(AdbPacket(AdbPacket.A_OPEN, localId, 0, payload))
            stream.awaitInitialOpen()
            return stream
        } catch (e: IOException) {
            streams.remove(localId)
            throw e
        }
    }

    val isOpen: Boolean get() = !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        transport.close()
        val cause = readerFailure ?: IOException("ADB connection closed")
        for (stream in streams.values) {
            stream.onRemoteClosed(cause)
        }
        streams.clear()
    }

    internal fun sendPacket(packet: AdbPacket) {
        ensureOpen()
        try {
            transport.writePacket(packet, skipChecksum)
        } catch (e: IOException) {
            readerFailure = e
            close()
            throw e
        }
    }

    internal fun removeStream(localId: Int) {
        streams.remove(localId)
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw readerFailure ?: IOException("ADB connection is closed")
        }
    }

    private fun runDemuxLoop() {
        try {
            while (!closed.get()) {
                val packet = transport.readPacket()
                dispatchPacket(packet)
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                readerFailure = e
                close()
            }
        }
    }

    private fun dispatchPacket(packet: AdbPacket) {
        val stream = streams[packet.arg1] ?: return
        when (packet.command) {
            AdbPacket.A_OKAY -> stream.onRemoteOkay(packet.arg0)

            AdbPacket.A_WRTE -> {
                stream.onRemoteWrite(packet.arg0, packet.payload)
                sendQuietly(AdbPacket(AdbPacket.A_OKAY, stream.localId, stream.remoteId))
            }

            AdbPacket.A_CLSE -> {
                streams.remove(packet.arg1)
                sendQuietly(AdbPacket(AdbPacket.A_CLSE, stream.localId, packet.arg0))
                stream.onRemoteClosed(null)
            }
        }
    }

    private fun sendQuietly(packet: AdbPacket) {
        try {
            if (!closed.get()) {
                transport.writePacket(packet, skipChecksum)
            }
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val MIN_MAX_DATA = 4_096

        fun connect(
            host: String,
            port: Int,
            transport: AdbPacketTransport,
            identity: AdbKeyIdentity,
            offerPublicKeyOnAuthPrompt: Boolean = false,
            streamIoTimeoutMs: Long = 15_000L
        ): AdbConnectionSession {
            var succeeded = false
            try {
                transport.writePacket(
                    AdbPacket(
                        command = AdbPacket.A_CNXN,
                        arg0 = AdbPacket.A_VERSION_SKIP_CHECKSUM,
                        arg1 = AdbPacket.DEFAULT_MAX_DATA,
                        payload = AdbPacket.HOST_CONNECT_BANNER
                    ),
                    skipChecksum = false
                )
                val handshakeResult = completeHandshake(
                    transport = transport,
                    identity = identity,
                    offerPublicKeyOnAuthPrompt = offerPublicKeyOnAuthPrompt
                )
                val device = parseBanner(
                    host = host,
                    port = port,
                    packet = handshakeResult.cnxnPacket,
                    isTls = handshakeResult.isTls
                )
                val skipChecksum =
                    (
                        handshakeResult.cnxnPacket.arg0.toUInt() >=
                            AdbPacket.A_VERSION_SKIP_CHECKSUM.toUInt()
                        )
                val session = AdbConnectionSession(
                    transport = transport,
                    connectedDevice = device,
                    skipChecksum = skipChecksum,
                    streamIoTimeoutMs = streamIoTimeoutMs
                )
                session.startDemux()
                succeeded = true
                return session
            } finally {
                if (!succeeded) {
                    transport.close()
                }
            }
        }

        private fun completeHandshake(
            transport: AdbPacketTransport,
            identity: AdbKeyIdentity,
            offerPublicKeyOnAuthPrompt: Boolean
        ): HandshakeOutcome {
            var packet = transport.readPacket()
            var isTls = false
            if (packet.command == AdbPacket.A_STLS) {
                transport.writePacket(
                    AdbPacket(AdbPacket.A_STLS, AdbPacket.A_STLS_VERSION, 0),
                    skipChecksum = false
                )
                transport.upgradeToTls(identity)
                isTls = true
                packet = transport.readPacket()
            } else if (packet.command == AdbPacket.A_AUTH) {
                packet = completeLegacyAuth(transport, identity, packet, offerPublicKeyOnAuthPrompt)
            }
            if (packet.command != AdbPacket.A_CNXN) {
                throw SSLHandshakeException(
                    "ADB peer did not complete CNXN (received ${AdbPacket.commandName(
                        packet.command
                    )})"
                )
            }
            return HandshakeOutcome(packet, isTls)
        }

        private fun completeLegacyAuth(
            transport: AdbPacketTransport,
            identity: AdbKeyIdentity,
            firstAuth: AdbPacket,
            offerPublicKey: Boolean
        ): AdbPacket {
            if (firstAuth.arg0 != AdbPacket.ADB_AUTH_TOKEN) {
                throw IOException("Unexpected ADB AUTH arg0: ${firstAuth.arg0}")
            }
            val signature = AndroidPubkeyCodec.signAdbAuthToken(
                identity.privateKey,
                firstAuth.payload
            )
            transport.writePacket(
                AdbPacket(AdbPacket.A_AUTH, AdbPacket.ADB_AUTH_SIGNATURE, 0, signature),
                skipChecksum = false
            )
            var next = transport.readPacket()
            if (next.command == AdbPacket.A_AUTH) {
                if (!offerPublicKey) {
                    throw SSLHandshakeException("ADB RSA key is unauthorized or expired")
                }
                transport.writePacket(
                    AdbPacket(
                        AdbPacket.A_AUTH,
                        AdbPacket.ADB_AUTH_RSAPUBLICKEY,
                        0,
                        identity.encodedAuthorizedKey()
                    ),
                    skipChecksum = false
                )
                next = transport.readPacket()
            }
            return next
        }

        private fun parseBanner(
            host: String,
            port: Int,
            packet: AdbPacket,
            isTls: Boolean
        ): AdbConnectedDevice {
            val raw = packet.payload
            val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
            val banner = String(raw, 0, end, Charsets.UTF_8)
            val propsPart = banner.substringAfter("::", missingDelimiterValue = "")
            val props = LinkedHashMap<String, String>()
            for (entry in propsPart.split(';')) {
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    props[entry.substring(0, eq)] = entry.substring(eq + 1)
                }
            }
            val features = props["features"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()
            return AdbConnectedDevice(
                host = host,
                port = port,
                banner = banner,
                productName = props["ro.product.name"],
                model = props["ro.product.model"],
                device = props["ro.product.device"],
                features = features,
                isTls = isTls,
                maxData = packet.arg1.coerceAtLeast(MIN_MAX_DATA)
            )
        }

        private data class HandshakeOutcome(val cnxnPacket: AdbPacket, val isTls: Boolean)
    }
}

private class MultiplexedAdbStream(
    private val session: AdbConnectionSession,
    override val service: String,
    val localId: Int,
    private val maxWriteChunk: Int,
    private val ioTimeoutMs: Long
) : AdbByteStream {
    private val stateLock = ReentrantLock()
    private val stateCondition = stateLock.newCondition()
    private val inboundChunks = LinkedBlockingDeque<InboundItem>()

    @Volatile
    var remoteId: Int = 0
        private set

    @Volatile
    private var opened = false

    @Volatile
    private var readyForWrite = false

    @Volatile
    private var streamClosed = false

    @Volatile
    private var failureCause: IOException? = null

    @Volatile
    private var readTimeoutMs: Int = 0

    override val isClosed: Boolean get() = streamClosed

    override fun setReadTimeout(timeoutMs: Int) {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0: $timeoutMs" }
        readTimeoutMs = timeoutMs
    }

    override val inputStream: InputStream = object : InputStream() {
        private var currentChunk: ByteArray = ByteArray(0)
        private var currentOffset = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count == -1) -1 else (single[0].toInt() and BYTE_MASK)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return if (!ensureChunkLoaded()) {
                -1
            } else {
                val toCopy = minOf(len, currentChunk.size - currentOffset)
                currentChunk.copyInto(b, off, currentOffset, currentOffset + toCopy)
                currentOffset += toCopy
                toCopy
            }
        }

        private fun ensureChunkLoaded(): Boolean {
            var eofReached = false
            while (!eofReached && currentOffset >= currentChunk.size) {
                val item = takeNextItem()
                when (item) {
                    is InboundItem.Data -> {
                        currentChunk = item.bytes
                        currentOffset = 0
                    }

                    is InboundItem.End -> {
                        item.error?.let { throw it }
                        inboundChunks.putFirst(item)
                        eofReached = true
                    }
                }
            }
            return !eofReached
        }

        private fun takeNextItem(): InboundItem = try {
            val timeout = readTimeoutMs
            if (timeout > 0) {
                inboundChunks.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException(
                        "Timed out reading ADB stream '$service' after ${timeout}ms"
                    )
            } else {
                inboundChunks.take()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(e.message)
        }

        override fun close() = this@MultiplexedAdbStream.close()
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var cursor = off
            val limit = off + len
            while (cursor < limit) {
                val sliceLen = minOf(maxWriteChunk, limit - cursor)
                val payload = b.copyOfRange(cursor, cursor + sliceLen)
                writeChunkWithFlowControl(payload)
                cursor += sliceLen
            }
        }

        override fun flush() = Unit

        override fun close() = this@MultiplexedAdbStream.close()
    }

    fun awaitInitialOpen() {
        stateLock.withLock {
            val deadline = System.currentTimeMillis() + ioTimeoutMs
            while (!opened && !streamClosed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    throw IOException("Timed out opening ADB stream '$service'")
                }
                stateCondition.await(remaining, TimeUnit.MILLISECONDS)
            }
            if (!opened) {
                throw failureCause ?: IOException("ADB peer rejected opening stream '$service'")
            }
        }
    }

    fun onRemoteOkay(peerId: Int) {
        stateLock.withLock {
            if (remoteId == 0) remoteId = peerId
            opened = true
            readyForWrite = true
            stateCondition.signalAll()
        }
    }

    fun onRemoteWrite(peerId: Int, payload: ByteArray) {
        stateLock.withLock {
            if (remoteId == 0) remoteId = peerId
        }
        if (payload.isNotEmpty() && !streamClosed) {
            inboundChunks.offer(InboundItem.Data(payload))
        }
    }

    fun onRemoteClosed(error: IOException?) {
        stateLock.withLock {
            if (streamClosed) return
            streamClosed = true
            failureCause = error
            inboundChunks.offer(InboundItem.End(error))
            stateCondition.signalAll()
        }
    }

    override fun close() {
        val peerToClose: Int
        stateLock.withLock {
            if (streamClosed) return
            streamClosed = true
            peerToClose = remoteId
            inboundChunks.offer(InboundItem.End(null))
            stateCondition.signalAll()
        }
        session.removeStream(localId)
        if (peerToClose != 0) {
            try {
                session.sendPacket(AdbPacket(AdbPacket.A_CLSE, localId, peerToClose))
            } catch (_: IOException) {
            }
        }
    }

    private fun writeChunkWithFlowControl(chunk: ByteArray) {
        val targetRemoteId: Int
        stateLock.withLock {
            val deadline = System.currentTimeMillis() + ioTimeoutMs
            while (!readyForWrite && !streamClosed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    throw IOException("Timed out waiting for ADB stream OKAY on '$service'")
                }
                stateCondition.await(remaining, TimeUnit.MILLISECONDS)
            }
            if (streamClosed) {
                throw failureCause ?: IOException("ADB stream '$service' is closed")
            }
            readyForWrite = false
            targetRemoteId = remoteId
        }
        session.sendPacket(AdbPacket(AdbPacket.A_WRTE, localId, targetRemoteId, chunk))
    }

    private sealed interface InboundItem {
        class Data(val bytes: ByteArray) : InboundItem
        class End(val error: IOException?) : InboundItem
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
