package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.VideoSize

/**
 * Connection target returned by [ServerLauncher.start] once the remote server is reachable.
 * [toString] deliberately omits [authenticator] so secrets or keys are never logged.
 */
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val authenticator: Authenticator,
    val sessionId: String? = null
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in MIN_PORT..MAX_PORT) { "port must be in $MIN_PORT..$MAX_PORT: $port" }
    }

    override fun toString(): String = "ServerEndpoint(host=$host, port=$port, sessionId=$sessionId)"

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }
}

/**
 * Immutable session state emitted by [SessionEngine.state].
 */
sealed interface SessionState {
    data object Idle : SessionState

    data class Discovering(val targetId: String? = null) : SessionState

    data class Connecting(val targetId: String? = null, val endpointHint: String? = null) :
        SessionState

    data class StartingServer(val targetId: String? = null) : SessionState

    data class Handshaking(val endpoint: ServerEndpoint, val attempt: Int = 1) : SessionState

    data class Streaming(
        val endpoint: ServerEndpoint,
        val negotiatedVersion: Int,
        val negotiatedFeatures: Int,
        val deviceInfo: DeviceInfo? = null,
        val videoSize: VideoSize? = null,
        val isDisplayPowerOn: Boolean = true,
        val isScreenLocked: Boolean = false,
        val bitrateBps: Int? = null,
        val fps: Int? = null,
        val rttMs: Long? = null,
        val serverCapabilities: Map<String, Boolean> = emptyMap(),
        val degradedCapabilities: Set<String> = emptySet(),
        val nonFatalError: SessionError? = null
    ) : SessionState

    data class Paused(
        val endpoint: ServerEndpoint,
        val negotiatedVersion: Int,
        val negotiatedFeatures: Int,
        val deviceInfo: DeviceInfo? = null,
        val videoSize: VideoSize? = null,
        val surfaceReleased: Boolean = true
    ) : SessionState

    data class Reconnecting(
        val attempt: Int,
        val nextRetryDelayMs: Long,
        val elapsedMs: Long,
        val maxDurationMs: Long,
        val lastError: SessionError,
        val relaunchingServer: Boolean = false
    ) : SessionState

    data class Failed(val error: SessionError) : SessionState
}
