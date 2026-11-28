package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.SessionCipher
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Minimal socket contract so [MirrorClient] is testable over real or fake streams. */
interface ClientSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isClosed: Boolean
}

/** Factory that opens a [ClientSocket] with configured connect and socket read timeouts. */
fun interface ClientSocketConnector {
    @Throws(IOException::class)
    fun connect(host: String, port: Int, readTimeoutMs: Int, connectTimeoutMs: Int): ClientSocket
}

private class JvmClientSocket(private val socket: Socket) : ClientSocket {
    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    override val isClosed: Boolean
        get() = socket.isClosed

    override fun close() {
        socket.close()
    }
}

object TcpClientSocketConnector : ClientSocketConnector {
    private const val RECV_BUFFER_BYTES = 512 * 1024
    private const val SEND_BUFFER_BYTES = 64 * 1024

    override fun connect(
        host: String,
        port: Int,
        readTimeoutMs: Int,
        connectTimeoutMs: Int
    ): ClientSocket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = readTimeoutMs
            socket.receiveBufferSize = RECV_BUFFER_BYTES
            socket.sendBufferSize = SEND_BUFFER_BYTES
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return JvmClientSocket(socket)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
    }
}

/**
 * Reusable protocol v2 [StreamTransport] backed by a dedicated reader coroutine, writer coroutine,
 * and preallocated [FrameBufferPool] / [ControlPacketPool] rings (PERF-03).
 */
class MirrorClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketConnector: ClientSocketConnector = TcpClientSocketConnector,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val frameBufferPool: FrameBufferPool = FrameBufferPool(),
    private val controlPacketPool: ControlPacketPool = ControlPacketPool(),
    private val clock: SessionClock = SystemSessionClock
) : StreamTransport {

    private val connectMutex = Mutex()

    @Volatile
    private var activeConnection: ActiveConnection? = null

    override suspend fun connect(
        endpoint: ServerEndpoint,
        readTimeoutMs: Long,
        scope: CoroutineScope
    ) {
        require(readTimeoutMs > 0L) { "readTimeoutMs must be positive" }
        val previous = connectMutex.withLock {
            activeConnection.also { activeConnection = null }
        }
        previous?.shutdown()

        val connector = resolveConnector(endpoint)
        val socket = withContext(ioDispatcher) {
            connector.connect(
                host = endpoint.host,
                port = endpoint.port,
                readTimeoutMs = readTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                connectTimeoutMs = connectTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }

        val connection = try {
            connectMutex.withLock {
                if (!scope.isActive) {
                    closeQuietly(socket)
                    throw IOException("Caller scope is inactive")
                }
                ActiveConnection(
                    socket = socket,
                    parentScope = scope,
                    ioDispatcher = ioDispatcher,
                    frameBufferPool = frameBufferPool,
                    controlPacketPool = controlPacketPool,
                    clock = clock
                ).also {
                    activeConnection = it
                }
            }
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
        connection.start()
    }

    override suspend fun installSessionCiphers(
        inboundCipher: SessionCipher,
        outboundCipher: SessionCipher
    ) {
        val conn = activeConnection ?: run {
            inboundCipher.close()
            outboundCipher.close()
            throw IOException("Transport not connected")
        }
        conn.installSessionCiphers(inboundCipher, outboundCipher)
    }

    override suspend fun setPhase(phase: Phase) {
        val conn = activeConnection ?: throw IOException("Transport not connected")
        conn.setPhase(phase)
    }

    override suspend fun readFrame(): ReceivedFrame? {
        val conn = activeConnection ?: throw IOException("Transport not connected")
        return conn.readFrame()
    }

    override suspend fun writeControl(command: ControlCommand) {
        val conn = activeConnection ?: throw IOException("Transport not connected")
        conn.writeControl(command)
    }

    override suspend fun writeControlPacket(packet: ControlPacket) {
        val conn = activeConnection ?: throw IOException("Transport not connected")
        conn.writeControlPacket(packet)
    }

    override suspend fun close() {
        val conn = connectMutex.withLock {
            activeConnection.also { activeConnection = null }
        }
        conn?.shutdown()
    }

    private fun resolveConnector(endpoint: ServerEndpoint): ClientSocketConnector =
        when (endpoint.dataChannelMode) {
            DataChannelMode.DIRECT_TCP -> socketConnector

            DataChannelMode.ADB_TUNNEL ->
                endpoint.tunnelConnector
                    ?: throw IOException("ADB tunnel mode requires a tunnelConnector")
        }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
    }
}

private sealed interface ReadSignal {
    data object Eof : ReadSignal
    data class Failure(val error: Exception) : ReadSignal
}

private sealed interface WriteRequest : OutboundWriteItem {
    val done: CompletableDeferred<Unit>

    fun failClosed() {
        done.completeExceptionally(IOException("Transport closed"))
    }

    data class SetCipher(val cipher: SessionCipher, override val done: CompletableDeferred<Unit>) :
        WriteRequest {
        override fun failClosed() {
            cipher.close()
            super.failClosed()
        }
    }

    data class SetPhase(val phase: Phase, override val done: CompletableDeferred<Unit>) :
        WriteRequest

    data class Command(val command: ControlCommand, override val done: CompletableDeferred<Unit>) :
        WriteRequest
}

private class ActiveConnection(
    private val socket: ClientSocket,
    parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    frameBufferPool: FrameBufferPool,
    private val controlPacketPool: ControlPacketPool,
    clock: SessionClock
) {
    private val connectionJob = Job(parentScope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(parentScope.coroutineContext + connectionJob)
    private val closing = AtomicBoolean(false)
    private val sessionPhaseGate = CompletableDeferred<Unit>()

    @Volatile
    private var pendingInboundCipher: SessionCipher? = null

    private val incomingFrames = Channel<Any>(
        capacity = INCOMING_CAPACITY,
        onUndeliveredElement = { item ->
            if (item is ReceivedFrame) item.release()
        }
    )
    private val outgoingRequests = Channel<OutboundWriteItem>(
        capacity = OUTGOING_CAPACITY,
        onUndeliveredElement = { item -> releaseUndeliveredOutbound(item) }
    )

    private val inboundReader = InboundFrameReader(
        socket = socket,
        closing = closing,
        sessionPhaseGate = sessionPhaseGate,
        inboundCipherProvider = { pendingInboundCipher },
        incomingFrames = incomingFrames,
        frameBufferPool = frameBufferPool,
        clock = clock
    )

    private val outboundWriter = OutboundControlWriter(
        socket = socket,
        outgoingRequests = outgoingRequests
    )

    init {
        connectionJob.invokeOnCompletion {
            closing.set(true)
            closeQuietly(socket)
            pendingInboundCipher?.close()
            sessionPhaseGate.cancel()
            outgoingRequests.close()
        }
    }

    fun start() {
        connectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watchCancellation()
        }
        connectionScope.launch(ioDispatcher) { inboundReader.run() }
        connectionScope.launch(ioDispatcher) { outboundWriter.run() }
    }

    private suspend fun watchCancellation() {
        try {
            suspendCancellableCoroutine<Nothing> { cont ->
                cont.invokeOnCancellation {
                    closing.set(true)
                    closeQuietly(socket)
                    sessionPhaseGate.cancel()
                    outgoingRequests.close()
                }
            }
        } catch (_: CancellationException) {
            // Normal connection teardown.
        }
    }

    suspend fun installSessionCiphers(inboundCipher: SessionCipher, outboundCipher: SessionCipher) {
        pendingInboundCipher = inboundCipher
        val done = CompletableDeferred<Unit>()
        enqueueWrite(WriteRequest.SetCipher(outboundCipher, done))
        done.await()
    }

    suspend fun setPhase(phase: Phase) {
        val done = CompletableDeferred<Unit>()
        enqueueWrite(WriteRequest.SetPhase(phase, done))
        done.await()
        if (phase == Phase.SESSION) {
            sessionPhaseGate.complete(Unit)
        }
    }

    suspend fun readFrame(): ReceivedFrame? =
        when (val item = incomingFrames.receiveCatching().getOrNull()) {
            null, ReadSignal.Eof -> null
            is ReceivedFrame -> item
            is ReadSignal.Failure -> throw item.error
            else -> null
        }

    suspend fun writeControl(command: ControlCommand) {
        val done = CompletableDeferred<Unit>()
        enqueueWrite(WriteRequest.Command(command, done))
        done.await()
    }

    suspend fun writeControlPacket(packet: ControlPacket) {
        val slot = controlPacketPool.acquire()
        slot.copyFrom(packet)
        try {
            outgoingRequests.send(slot)
        } catch (e: ClosedSendChannelException) {
            slot.release()
            throw IOException("Transport closed", e)
        }
    }

    suspend fun shutdown() {
        withContext(NonCancellable) {
            closing.set(true)
            closeQuietly(socket)
            pendingInboundCipher?.close()
            sessionPhaseGate.cancel()
            outgoingRequests.close()
            connectionJob.cancel()
            connectionJob.join()
            while (true) {
                val item = incomingFrames.tryReceive().getOrNull() ?: break
                if (item is ReceivedFrame) item.release()
            }
        }
    }

    private suspend fun enqueueWrite(request: WriteRequest) {
        try {
            outgoingRequests.send(request)
        } catch (e: ClosedSendChannelException) {
            request.failClosed()
            throw IOException("Transport closed", e)
        }
    }

    private companion object {
        const val INCOMING_CAPACITY = 8
        const val OUTGOING_CAPACITY = 64
    }
}

private class InboundFrameReader(
    private val socket: ClientSocket,
    private val closing: AtomicBoolean,
    private val sessionPhaseGate: CompletableDeferred<Unit>,
    private val inboundCipherProvider: () -> SessionCipher?,
    private val incomingFrames: Channel<Any>,
    private val frameBufferPool: FrameBufferPool,
    private val clock: SessionClock
) {
    suspend fun run() {
        val reader = FrameReader(BufferedInputStream(socket.inputStream, READ_BUFFER_BYTES))
        val header = FrameHeader()
        try {
            readLoop(reader, header)
        } catch (e: ProtocolException) {
            if (!closing.get()) incomingFrames.send(ReadSignal.Failure(e))
        } catch (e: IOException) {
            if (!closing.get()) incomingFrames.send(ReadSignal.Failure(e))
        } finally {
            reader.cipher?.close()
            reader.cipher = null
            incomingFrames.close()
        }
    }

    private suspend fun readLoop(reader: FrameReader, header: FrameHeader) {
        while (currentCoroutineContext().isActive && !closing.get()) {
            if (reader.phase == Phase.HANDSHAKE && sessionPhaseGate.isCompleted) {
                reader.cipher = inboundCipherProvider()
                reader.phase = Phase.SESSION
            }
            val hasNext = reader.readHeader(header)
            if (!hasNext) {
                incomingFrames.send(ReadSignal.Eof)
                break
            }
            val frame = readFramePayload(reader, header)
            val waitForSessionPhase =
                reader.phase == Phase.HANDSHAKE && header.type == MessageType.AUTH_PROOF
            try {
                incomingFrames.send(frame)
            } catch (e: ClosedSendChannelException) {
                frame.release()
                throw IOException("Incoming frame channel closed", e)
            }
            if (waitForSessionPhase) {
                sessionPhaseGate.await()
                reader.cipher = inboundCipherProvider()
                reader.phase = Phase.SESSION
            }
        }
    }

    private fun readFramePayload(reader: FrameReader, header: FrameHeader): ReceivedFrame {
        if (header.type == MessageType.VIDEO) {
            val pooled = frameBufferPool.acquireVideoFrame(header.length)
            try {
                reader.readPayload(header, pooled.payload, 0)
            } catch (e: ProtocolException) {
                pooled.release()
                throw e
            } catch (e: IOException) {
                pooled.release()
                throw e
            }
            pooled.updateHeader(
                type = header.type,
                flags = header.flags,
                seq = header.seq,
                ptsUs = header.ptsUs,
                captureUs = header.captureUs,
                receivedAtUs = clock.monotonicMicros()
            )
            return pooled
        }
        val length = reader.readPayload(header)
        return ReceivedFrame(
            type = header.type,
            flags = header.flags,
            seq = header.seq,
            ptsUs = header.ptsUs,
            captureUs = header.captureUs,
            payload = reader.payload.copyOf(length),
            payloadLength = length,
            receivedAtUs = clock.monotonicMicros()
        )
    }

    private companion object {
        const val READ_BUFFER_BYTES = 256 * 1024
    }
}

private class OutboundControlWriter(
    private val socket: ClientSocket,
    private val outgoingRequests: Channel<OutboundWriteItem>
) {
    suspend fun run() {
        val out = socket.outputStream
        val writer = ControlWriter(out)
        try {
            for (item in outgoingRequests) {
                when (item) {
                    is PooledControlSlot -> handlePooledControlWrite(writer, out, item)
                    is WriteRequest -> handleWriteRequest(writer, out, item)
                }
            }
        } finally {
            writer.cipher?.close()
            writer.cipher = null
            outgoingRequests.close()
            while (true) {
                val leftover = outgoingRequests.tryReceive().getOrNull() ?: break
                releaseUndeliveredOutbound(leftover)
            }
        }
    }

    private fun handlePooledControlWrite(
        writer: ControlWriter,
        out: OutputStream,
        slot: PooledControlSlot
    ) {
        try {
            slot.packet.seq = writer.nextSeq
            writer.write(slot.packet)
            writer.nextSeq = (writer.nextSeq + 1) and SEQ_MASK
            out.flush()
        } catch (_: ProtocolException) {
            closeQuietly(socket)
        } catch (_: IOException) {
            closeQuietly(socket)
        } finally {
            slot.release()
        }
    }

    private fun handleWriteRequest(
        writer: ControlWriter,
        out: OutputStream,
        request: WriteRequest
    ) {
        when (request) {
            is WriteRequest.SetCipher -> {
                writer.cipher?.close()
                writer.cipher = request.cipher
                request.done.complete(Unit)
            }

            is WriteRequest.SetPhase -> {
                writer.phase = request.phase
                request.done.complete(Unit)
            }

            is WriteRequest.Command -> {
                try {
                    writeControlCommand(writer, request.command)
                    out.flush()
                    request.done.complete(Unit)
                } catch (e: ProtocolException) {
                    request.done.completeExceptionally(e)
                } catch (e: IOException) {
                    closeQuietly(socket)
                    request.done.completeExceptionally(e)
                }
            }
        }
    }

    private companion object {
        const val SEQ_MASK = 0xFFFF
    }
}

private fun releaseUndeliveredOutbound(item: OutboundWriteItem) {
    when (item) {
        is PooledControlSlot -> item.release()
        is WriteRequest -> item.failClosed()
    }
}

private fun writeControlCommand(writer: ControlWriter, command: ControlCommand) {
    when (command) {
        is ControlCommand.HelloCommand -> writer.writePayload(command.message)

        is ControlCommand.AuthProofCommand -> writer.writePayload(command.message)

        is ControlCommand.Touch ->
            writer.touch(command.pointer, command.action, command.x, command.y)

        is ControlCommand.Key -> writer.key(command.keyCode, command.action)

        is ControlCommand.Text -> writer.writePayload(command.message)

        is ControlCommand.Clipboard -> writer.writePayload(command.message)

        is ControlCommand.ConfigStream ->
            writer.configStream(command.bitrateBps, command.fps, command.scale)

        ControlCommand.KeyframeRequest -> writer.request(MessageType.KEYFRAME_REQ)

        ControlCommand.Pause -> writer.request(MessageType.PAUSE)

        ControlCommand.Resume -> writer.request(MessageType.RESUME)

        is ControlCommand.Action -> writer.action(command.action)

        is ControlCommand.Ping -> writer.ping(command.token)

        is ControlCommand.Bye -> writer.bye(command.code)
    }
}

private fun closeQuietly(closeable: Closeable?) {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (_: IOException) {
        // Best-effort socket cleanup on teardown.
    }
}
