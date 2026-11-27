package com.mastersoft.velocast.client

import android.view.Surface
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.KeyAction
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.VideoSize
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Facade wiring [SessionEngine], [MirrorClient], [VideoFrameQueue], [VideoDecoder],
 * [TouchPipeline], [ViewportMapper], and [SessionMetrics] into a single client-core session
 * controller.
 */
class ClientMediaSession(
    serverLauncher: ServerLauncher,
    private val scope: CoroutineScope,
    config: SessionConfig = SessionConfig(),
    private val clock: SessionClock = SystemSessionClock,
    codecFactory: DecoderCodecFactory = MediaCodecDecoderFactory,
    val frameBufferPool: FrameBufferPool = FrameBufferPool(),
    val controlPacketPool: ControlPacketPool = ControlPacketPool(),
    private val transport: StreamTransport = MirrorClient(
        frameBufferPool = frameBufferPool,
        controlPacketPool = controlPacketPool,
        clock = clock
    ),
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onFrameRendered: () -> Unit = {}
) {
    val metrics = SessionMetrics(clock = clock)
    val viewportMapper = ViewportMapper()
    val touchPipeline =
        TouchPipeline(transport = transport, scope = scope, viewportMapper = viewportMapper)

    private val keyframeSignal = Channel<Unit>(Channel.CONFLATED)
    private val keyframePacket = ControlPacket().apply { type = MessageType.KEYFRAME_REQ }
    private val keyPacket = ControlPacket().apply { type = MessageType.KEY }
    private val actionPacket = ControlPacket().apply { type = MessageType.ACTION }

    private var decoderConsumerJob: Job? = null
    private var keyframeSenderJob: Job? = null

    val frameQueue: VideoFrameQueue = VideoFrameQueue(
        clock = clock,
        onRequestKeyframe = { requestKeyframe("queue_overflow") },
        onFrameDropped = { metrics.recordQueueDrop() },
        onFrameSkipped = { metrics.recordSkippedNonKeyframe() }
    )

    val decoder: VideoDecoder = VideoDecoder(
        codecFactory = codecFactory,
        clock = clock,
        metrics = metrics,
        onNeedKeyframe = { requestKeyframe("decoder_recovery") },
        onVideoSizeChanged = { width, height ->
            viewportMapper.updateStreamDimensions(width, height)
            onVideoSizeChanged(width, height)
        },
        onFrameRendered = onFrameRendered,
        onFatalError = { error ->
            scope.launch { sessionEngine.failWith(error) }
        }
    )

    private val frameConsumer = object : SessionFrameConsumer {
        override fun onVideoSize(videoSize: VideoSize) {
            viewportMapper.updateVideoSize(videoSize)
            decoder.onVideoSize(videoSize)
            onVideoSizeChanged(videoSize.streamWidth, videoSize.streamHeight)
        }

        override fun onCodecConfig(config: CodecConfig, flags: Int) {
            decoder.onCodecConfig(config, flags)
        }

        override fun onVideoFrame(frame: ReceivedFrame) {
            frameQueue.enqueue(frame)
        }

        override fun onStatus(status: Status) {
            if (status.kind == StatusKind.DROPPED_FRAMES && status.value > 0) {
                metrics.recordQueueDrop(status.value.toLong())
                frameQueue.notifyExternalDropOrReset(sendKeyframeRequest = true)
            }
        }

        override fun onPongMicros(pong: Pong, sentAtUs: Long?, receivedAtUs: Long) {
            if (sentAtUs != null) {
                metrics.recordPong(t0Us = sentAtUs, t1Us = receivedAtUs, serverUs = pong.serverUs)
            }
        }
    }

    private val lifecycleHooks = object : SessionLifecycleHooks {
        override suspend fun onBeforePause() {
            touchPipeline.flushCancelOnPauseOrDisconnect()
            frameQueue.clear(requestKeyframeAfter = false)
        }

        override suspend fun onBeforeDisconnect() {
            touchPipeline.flushCancelOnPauseOrDisconnect()
        }

        override fun onConnectionLost() {
            touchPipeline.resetOnTransportLost()
            frameQueue.clear(requestKeyframeAfter = false)
            metrics.resetConnectionSync()
        }

        override fun onSessionEnded() {
            touchPipeline.resetOnTransportLost()
            frameQueue.clear(requestKeyframeAfter = false)
            metrics.resetConnectionSync()
        }
    }

    val sessionEngine: SessionEngine = SessionEngine(
        serverLauncher = serverLauncher,
        transport = transport,
        parentScope = scope,
        config = config,
        clock = clock,
        surfaceController = SurfaceController { decoder.releaseSurface() },
        frameConsumer = frameConsumer,
        lifecycleHooks = lifecycleHooks
    )

    val state: StateFlow<SessionState> = sessionEngine.state
    val decoderState: StateFlow<DecoderState> = decoder.state
    val metricsState: StateFlow<SessionMetricsSnapshot> = metrics.state

    init {
        decoderConsumerJob = scope.launch {
            while (true) {
                val frame = frameQueue.receiveNext() ?: break
                decoder.feedFrame(frame)
            }
        }
        keyframeSenderJob = scope.launch {
            for (signal in keyframeSignal) {
                if (sessionEngine.state.value is SessionState.Streaming) {
                    try {
                        transport.writeControlPacket(keyframePacket)
                    } catch (_: IOException) {
                        // Liveness watchdog handles broken transport.
                    }
                }
            }
        }
    }

    fun attachSurface(surface: Surface) {
        decoder.start(surface)
        decoder.attachSurface(surface)
    }

    fun detachSurface() {
        decoder.releaseSurface()
    }

    fun connect() {
        sessionEngine.connect()
    }

    suspend fun pause() {
        sessionEngine.pause()
    }

    suspend fun resume() {
        sessionEngine.resume()
    }

    fun retryNow() {
        sessionEngine.retryNow()
    }

    fun requestKeyframe(reason: String = "manual") {
        metrics.recordKeyframeRequest(reason)
        keyframeSignal.trySend(Unit)
    }

    fun sendKeyPress(keyCode: Int) {
        scope.launch {
            if (sessionEngine.state.value !is SessionState.Streaming) return@launch
            try {
                keyPacket.type = MessageType.KEY
                keyPacket.seq = 0
                keyPacket.param = keyCode
                keyPacket.x = 0f
                keyPacket.y = 0f
                keyPacket.pointer = KeyAction.DOWN.id
                transport.writeControlPacket(keyPacket)
                keyPacket.pointer = KeyAction.UP.id
                transport.writeControlPacket(keyPacket)
            } catch (_: IOException) {
                // Handled by session liveness monitor.
            }
        }
    }

    fun sendDeviceAction(action: DeviceAction) {
        scope.launch {
            if (sessionEngine.state.value !is SessionState.Streaming) return@launch
            try {
                actionPacket.type = MessageType.ACTION
                actionPacket.pointer = 0
                actionPacket.seq = 0
                actionPacket.param = action.id
                actionPacket.x = 0f
                actionPacket.y = 0f
                transport.writeControlPacket(actionPacket)
            } catch (_: IOException) {
                // Handled by session liveness monitor.
            }
        }
    }

    suspend fun disconnect() {
        sessionEngine.disconnect()
    }

    fun close() {
        sessionEngine.close()
        touchPipeline.close()
        frameQueue.close()
        decoder.stop()
        decoderConsumerJob?.cancel()
        keyframeSenderJob?.cancel()
        keyframeSignal.close()
    }
}
