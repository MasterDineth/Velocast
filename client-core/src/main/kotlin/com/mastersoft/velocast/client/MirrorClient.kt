package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolException
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
 * Reusable protocol v2 [StreamTransport] backed by a dedicated reader coroutine and writer
 * coroutine attached to the caller's structured [CoroutineScope].
 */
class MirrorClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketConnector: ClientSocketConnector = TcpClientSocketConnector,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
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

        val socket = withContext(ioDispatcher) {
            socketConnector.connect(
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
                ActiveConnection(socket, scope, ioDispatcher).also {
                    activeConnection = it
                }
            }
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
        connection.start()
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

    override suspend fun close() {
        val conn = connectMutex.withLock {
            activeConnection.also { activeConnection = null }
        }
        conn?.shutdown()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
    }
}

private sealed interface ReadOutcome {
    data class Frame(val frame: ReceivedFrame) : ReadOutcome
    data object Eof : ReadOutcome
    data class Failure(val error: Exception) : ReadOutcome
}

private sealed interface WriteRequest {
    val done: CompletableDeferred<Unit>

    fun failClosed() {
        done.completeExceptionally(IOException("Transport closed"))
    }

    data class SetPhase(val phase: Phase, override val done: CompletableDeferred<Unit>) :
        WriteRequest

    data class Command(val command: ControlCommand, override val done: CompletableDeferred<Unit>) :
        WriteRequest
}

private class ActiveConnection(
    private val socket: ClientSocket,
    parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) {
    private val connectionJob = Job(parentScope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(parentScope.coroutineContext + connectionJob)
    private val closing = AtomicBoolean(false)
    private val sessionPhaseGate = CompletableDeferred<Unit>()
    private val incomingFrames = Channel<ReadOutcome>(capacity = INCOMING_CAPACITY)
    private val outgoingRequests = Channel<WriteRequest>(
        capacity = OUTGOING_CAPACITY,
        onUndeliveredElement = { it.failClosed() }
    )

    init {
        connectionJob.invokeOnCompletion {
            closing.set(true)
            closeQuietly(socket)
            sessionPhaseGate.cancel()
            outgoingRequests.close()
        }
    }

    fun start() {
        connectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watchCancellation()
        }
        connectionScope.launch(ioDispatcher) { runReader() }
        connectionScope.launch(ioDispatcher) { runWriter() }
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

    suspend fun setPhase(phase: Phase) {
        val done = CompletableDeferred<Unit>()
        enqueueWrite(WriteRequest.SetPhase(phase, done))
        done.await()
        if (phase == Phase.SESSION) {
            sessionPhaseGate.complete(Unit)
        }
    }

    suspend fun readFrame(): ReceivedFrame? {
        val outcome = incomingFrames.receiveCatching().getOrNull() ?: return null
        return when (outcome) {
            is ReadOutcome.Frame -> outcome.frame
            is ReadOutcome.Eof -> null
            is ReadOutcome.Failure -> throw outcome.error
        }
    }

    suspend fun writeControl(command: ControlCommand) {
        val done = CompletableDeferred<Unit>()
        enqueueWrite(WriteRequest.Command(command, done))
        done.await()
    }

    suspend fun shutdown() {
        withContext(NonCancellable) {
            closing.set(true)
            closeQuietly(socket)
            sessionPhaseGate.cancel()
            outgoingRequests.close()
            connectionJob.cancel()
            connectionJob.join()
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

    private suspend fun runReader() {
        val reader = FrameReader(BufferedInputStream(socket.inputStream, READ_BUFFER_BYTES))
        val header = FrameHeader()
        try {
            readLoop(reader, header)
        } catch (e: ProtocolException) {
            if (!closing.get()) {
                incomingFrames.send(ReadOutcome.Failure(e))
            }
        } catch (e: IOException) {
            if (!closing.get()) {
                incomingFrames.send(ReadOutcome.Failure(e))
            }
        } finally {
            incomingFrames.close()
        }
    }

    private suspend fun readLoop(reader: FrameReader, header: FrameHeader) {
        while (currentCoroutineContext().isActive && !closing.get()) {
            if (reader.phase == Phase.HANDSHAKE && sessionPhaseGate.isCompleted) {
                reader.phase = Phase.SESSION
            }
            val hasNext = reader.readHeader(header)
            if (!hasNext) {
                incomingFrames.send(ReadOutcome.Eof)
                break
            }
            val length = reader.readPayload(header)
            val payload = reader.payload.copyOf(length)
            val frame = ReceivedFrame(
                type = header.type,
                flags = header.flags,
                seq = header.seq,
                ptsUs = header.ptsUs,
                captureUs = header.captureUs,
                payload = payload
            )
            val waitForSessionPhase =
                reader.phase == Phase.HANDSHAKE && header.type == MessageType.AUTH_PROOF
            incomingFrames.send(ReadOutcome.Frame(frame))
            if (waitForSessionPhase) {
                sessionPhaseGate.await()
                reader.phase = Phase.SESSION
            }
        }
    }

    private suspend fun runWriter() {
        val out = socket.outputStream
        val writer = ControlWriter(out)
        try {
            for (request in outgoingRequests) {
                handleWriteRequest(writer, out, request)
            }
        } finally {
            outgoingRequests.close()
            while (true) {
                val leftover = outgoingRequests.tryReceive().getOrNull() ?: break
                leftover.failClosed()
            }
        }
    }

    private fun handleWriteRequest(
        writer: ControlWriter,
        out: OutputStream,
        request: WriteRequest
    ) {
        when (request) {
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
        const val INCOMING_CAPACITY = 32
        const val OUTGOING_CAPACITY = 64
        const val READ_BUFFER_BYTES = 256 * 1024
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
