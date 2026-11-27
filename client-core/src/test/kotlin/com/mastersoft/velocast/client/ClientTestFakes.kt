package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Payload
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.ServerHandshake
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.VideoSize
import com.mastersoft.velocast.protocol.testing.HmacTestAuthenticator
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestSchedulerClock(private val scheduler: TestCoroutineScheduler) : SessionClock {
    override fun monotonicMillis(): Long = scheduler.currentTime
}

internal class FakeSurfaceController : SurfaceController {
    var releaseCount: Int = 0
        private set

    override fun releaseSurface() {
        releaseCount += 1
    }
}

internal class RecordingFrameConsumer : SessionFrameConsumer {
    val deviceInfos = mutableListOf<DeviceInfo>()
    val codecConfigs = mutableListOf<Pair<CodecConfig, Int>>()
    val videoSizes = mutableListOf<VideoSize>()
    val videoFrames = mutableListOf<ReceivedFrame>()
    val statuses = mutableListOf<Status>()
    val clipboards = mutableListOf<ClipboardMessage>()
    val pongs = mutableListOf<Pong>()
    val nonFatalErrors = mutableListOf<SessionError>()

    override fun onDeviceInfo(deviceInfo: DeviceInfo) {
        deviceInfos += deviceInfo
    }

    override fun onCodecConfig(config: CodecConfig, flags: Int) {
        codecConfigs += config to flags
    }

    override fun onVideoSize(videoSize: VideoSize) {
        videoSizes += videoSize
    }

    override fun onVideoFrame(frame: ReceivedFrame) {
        videoFrames += frame.copyUnpooled()
        frame.release()
    }

    override fun onStatus(status: Status) {
        statuses += status
    }

    override fun onClipboard(clipboard: ClipboardMessage) {
        clipboards += clipboard
    }

    override fun onPong(pong: Pong, sentAtMs: Long?, receivedAtMs: Long) {
        pongs += pong
    }

    override fun onNonFatalError(error: SessionError) {
        nonFatalErrors += error
    }
}

internal class FakeServerLauncher(
    private val defaultEndpoint: ServerEndpoint = defaultTestEndpoint(),
    private val emitPhases: Boolean = true
) : ServerLauncher {
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    var onStart: suspend (attempt: Int, onPhase: (LaunchPhase) -> Unit) -> ServerEndpoint =
        { _, onPhase ->
            if (emitPhases) {
                onPhase(LaunchPhase.DISCOVERING)
                yield()
                onPhase(LaunchPhase.CONNECTING)
                yield()
                onPhase(LaunchPhase.STARTING_SERVER)
                yield()
            }
            defaultEndpoint
        }

    override suspend fun start(onPhase: (LaunchPhase) -> Unit): ServerEndpoint {
        startCount += 1
        return onStart(startCount, onPhase)
    }

    override suspend fun stop() {
        stopCount += 1
    }
}

internal sealed interface InboundEvent {
    data class Frame(val frame: ReceivedFrame) : InboundEvent
    data object Eof : InboundEvent
    data class Failure(val error: Exception) : InboundEvent
}

internal data class ConnectRecord(val endpoint: ServerEndpoint, val readTimeoutMs: Long)

internal class FakeStreamTransport(
    private val serverAuthenticator: Authenticator = defaultTestAuthenticator(),
    private val serverCapabilities: Int = SessionConfig.DEFAULT_WANTED_FEATURES
) : StreamTransport {
    val connectRecords = mutableListOf<ConnectRecord>()
    val sentCommands = mutableListOf<ControlCommand>()
    val phaseHistory = mutableListOf<Phase>()
    var closeCount: Int = 0
        private set
    var currentPhase: Phase = Phase.HANDSHAKE
        private set
    var autoReplyPong: Boolean = true
    var autoHandshake: Boolean = true

    var onConnect: suspend (attempt: Int, endpoint: ServerEndpoint) -> Unit = { _, _ -> }
    var onWrite: suspend (command: ControlCommand) -> Unit = {}

    private var inboundQueue = Channel<InboundEvent>(Channel.UNLIMITED)
    private var serverHandshake: ServerHandshake? = null
    private var childJob: Job? = null
    private var holdChildAlive: CompletableDeferred<Unit>? = null
    var activeChildCount: Int = 0
        private set

    override suspend fun connect(
        endpoint: ServerEndpoint,
        readTimeoutMs: Long,
        scope: CoroutineScope
    ) {
        connectRecords += ConnectRecord(endpoint, readTimeoutMs)
        currentPhase = Phase.HANDSHAKE
        inboundQueue = Channel(Channel.UNLIMITED)
        serverHandshake = ServerHandshake(
            authenticator = serverAuthenticator,
            capabilities = serverCapabilities,
            serverNonce = ByteArray(ProtocolV2.NONCE_SIZE) { index -> (index + 16).toByte() }
        )
        val gate = CompletableDeferred<Unit>()
        holdChildAlive = gate
        activeChildCount += 1
        childJob = scope.launch {
            try {
                gate.await()
            } finally {
                activeChildCount -= 1
            }
        }
        onConnect(connectRecords.size, endpoint)
    }

    override suspend fun setPhase(phase: Phase) {
        currentPhase = phase
        phaseHistory += phase
    }

    override suspend fun readFrame(): ReceivedFrame? =
        when (val event = inboundQueue.receiveCatching().getOrNull()) {
            null, InboundEvent.Eof -> null
            is InboundEvent.Frame -> event.frame
            is InboundEvent.Failure -> throw event.error
        }

    override suspend fun writeControl(command: ControlCommand) {
        sentCommands += command
        onWrite(command)
        when (command) {
            is ControlCommand.HelloCommand -> {
                if (autoHandshake) {
                    val reply = serverHandshake!!.onHello(
                        command.message.encode(),
                        0,
                        ProtocolV2.HELLO_SIZE
                    )
                    enqueuePayload(reply)
                }
            }

            is ControlCommand.AuthProofCommand -> {
                if (autoHandshake) {
                    val proof = serverHandshake!!.onClientProof(
                        command.message.encode(),
                        0,
                        ProtocolV2.PROOF_SIZE
                    )
                    enqueuePayload(proof)
                }
            }

            is ControlCommand.Ping -> {
                if (autoReplyPong && currentPhase == Phase.SESSION) {
                    enqueuePayload(Pong(command.token, serverUs = 1_000L))
                }
            }

            else -> Unit
        }
    }

    override suspend fun close() {
        closeCount += 1
        holdChildAlive?.cancel()
        childJob?.cancel()
        inboundQueue.close()
    }

    fun enqueuePayload(payload: Payload, flags: Int = defaultFlags(payload.type), seq: Int = 1) {
        inboundQueue.trySend(
            InboundEvent.Frame(
                ReceivedFrame(
                    type = payload.type,
                    flags = flags,
                    seq = seq,
                    ptsUs = 0L,
                    captureUs = 0L,
                    payload = payload.encode()
                )
            )
        )
    }

    fun enqueueFrame(frame: ReceivedFrame) {
        inboundQueue.trySend(InboundEvent.Frame(frame))
    }

    fun enqueueFailure(error: Exception) {
        inboundQueue.trySend(InboundEvent.Failure(error))
    }

    fun enqueueEof() {
        inboundQueue.trySend(InboundEvent.Eof)
    }

    private fun defaultFlags(type: MessageType): Int = when (type) {
        MessageType.CONFIG -> FrameFlags.CONFIG
        MessageType.ERROR -> FrameFlags.ERROR
        MessageType.VIDEO_SIZE -> FrameFlags.RESET
        else -> 0
    }
}

internal fun defaultTestAuthenticator(): Authenticator =
    HmacTestAuthenticator(ByteArray(32) { idx -> (idx + 1).toByte() })

internal fun defaultTestEndpoint(
    host: String = "192.0.2.10",
    port: Int = ProtocolV2.DEFAULT_PORT,
    authenticator: Authenticator = defaultTestAuthenticator(),
    sessionId: String = "session-1"
): ServerEndpoint = ServerEndpoint(host, port, authenticator, sessionId)
