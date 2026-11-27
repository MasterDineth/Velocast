package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.Features
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.Validators
import com.mastersoft.velocast.protocol.VideoSize

/** Progress steps reported by [ServerLauncher.start]. */
enum class LaunchPhase {
    DISCOVERING,
    CONNECTING,
    STARTING_SERVER
}

/**
 * Starts and stops the remote server (ADB Wi-Fi, USB OTG, or Host-assisted) and yields the
 * [ServerEndpoint] to connect to.
 */
interface ServerLauncher {
    suspend fun start(onPhase: (LaunchPhase) -> Unit = {}): ServerEndpoint

    suspend fun stop()
}

/** Releases the viewer's rendering surface when the app moves to the background. */
fun interface SurfaceController {
    fun releaseSurface()
}

object NoOpSurfaceController : SurfaceController {
    override fun releaseSurface() = Unit
}

/** Monotonic clock abstraction so liveness and backoff timing are testable without real time. */
interface SessionClock {
    fun monotonicMillis(): Long

    fun monotonicMicros(): Long = monotonicMillis() * MICROS_PER_MILLI

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}

object SystemSessionClock : SessionClock {
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L

    override fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

    override fun monotonicMicros(): Long = System.nanoTime() / NANOS_PER_MICRO
}

/** Downstream media/control frame consumer wired in S04B. */
interface SessionFrameConsumer {
    fun onDeviceInfo(deviceInfo: DeviceInfo) = Unit

    fun onCodecConfig(config: CodecConfig, flags: Int) = Unit

    fun onVideoSize(videoSize: VideoSize) = Unit

    fun onVideoFrame(frame: ReceivedFrame) = frame.release()

    fun onStatus(status: Status) = Unit

    fun onClipboard(clipboard: ClipboardMessage) = Unit

    fun onPong(pong: Pong, sentAtMs: Long?, receivedAtMs: Long) = Unit

    fun onPongMicros(pong: Pong, sentAtUs: Long?, receivedAtUs: Long) = Unit

    fun onNonFatalError(error: SessionError) = Unit
}

object NoOpSessionFrameConsumer : SessionFrameConsumer

/** Hooks invoked by [SessionEngine] around lifecycle transitions for touch/media cleanup. */
interface SessionLifecycleHooks {
    suspend fun onBeforePause() = Unit

    suspend fun onBeforeDisconnect() = Unit

    fun onConnectionLost() = Unit

    fun onSessionEnded() = Unit
}

object NoOpSessionLifecycleHooks : SessionLifecycleHooks

data class StreamConfig(val bitrateBps: Int, val fps: Int, val scale: Float) {
    init {
        Validators.bitrateBps(bitrateBps)
        Validators.fps(fps.toFloat())
        Validators.scale(scale)
    }
}

/**
 * Exponential backoff schedule for reconnects: 1s, 2s, 4s, 8s, then capped at 15s for up to
 * 10 minutes by default.
 */
data class ReconnectConfig(
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS
) {
    init {
        require(initialDelayMs > 0L) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(maxDurationMs >= 0L) { "maxDurationMs must be >= 0" }
    }

    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        var delay = initialDelayMs
        repeat(attempt - 1) {
            if (delay >= maxDelayMs) return maxDelayMs
            delay = if (delay > maxDelayMs / 2L) maxDelayMs else delay * 2L
        }
        return delay.coerceAtMost(maxDelayMs)
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 15_000L
        const val DEFAULT_MAX_DURATION_MS = 600_000L
    }
}

data class SessionConfig(
    val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    val socketReadTimeoutMs: Long = DEFAULT_SOCKET_READ_TIMEOUT_MS,
    val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    val reconnect: ReconnectConfig = ReconnectConfig(),
    val wantedFeatures: Int = DEFAULT_WANTED_FEATURES,
    val initialStreamConfig: StreamConfig? = null,
    val targetId: String? = null
) {
    init {
        require(heartbeatIntervalMs > 0L) { "heartbeatIntervalMs must be positive" }
        require(socketReadTimeoutMs > 0L) { "socketReadTimeoutMs must be positive" }
        require(silenceTimeoutMs > heartbeatIntervalMs) {
            "silenceTimeoutMs must be > heartbeatIntervalMs"
        }
        require(handshakeTimeoutMs > 0L) { "handshakeTimeoutMs must be positive" }
    }

    companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000L
        const val DEFAULT_SOCKET_READ_TIMEOUT_MS = 5_000L
        const val DEFAULT_SILENCE_TIMEOUT_MS = 6_000L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000L
        const val DEFAULT_WANTED_FEATURES =
            Features.CLIPBOARD or
                Features.TEXT_INPUT or
                Features.DISPLAY_POWER or
                Features.ROTATION_EVENTS
    }
}
