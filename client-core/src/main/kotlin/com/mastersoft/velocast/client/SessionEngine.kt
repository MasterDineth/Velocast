package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.ProtocolV2
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-owner state machine driving discovery, server launch, mutual authentication, liveness
 * monitoring, pause/resume, and exponential-backoff reconnection.
 */
class SessionEngine(
    private val serverLauncher: ServerLauncher,
    private val transport: StreamTransport,
    private val parentScope: CoroutineScope,
    private val config: SessionConfig = SessionConfig(),
    private val clock: SessionClock = SystemSessionClock,
    private val surfaceController: SurfaceController = NoOpSurfaceController,
    private val frameConsumer: SessionFrameConsumer = NoOpSessionFrameConsumer,
    private val nonceGenerator: () -> ByteArray = defaultNonceGenerator,
    private val lifecycleHooks: SessionLifecycleHooks = NoOpSessionLifecycleHooks
) {
    private val lifecycleMutex = Mutex()
    private val pauseMutex = Mutex()
    private val stateHolder = SessionStateHolder()
    private val closed = AtomicBoolean(false)

    val state: StateFlow<SessionState> = stateHolder.flow

    @Volatile
    private var sessionJob: Job? = null

    /** Starts connecting if currently [SessionState.Idle] or [SessionState.Failed]. */
    fun connect() {
        val current = stateHolder.current
        val canStart =
            !closed.get() &&
                parentScope.isActive &&
                (current is SessionState.Idle || current is SessionState.Failed) &&
                sessionJob?.isActive != true
        if (!canStart) return

        if (current is SessionState.Failed) {
            stateHolder.set(SessionState.Idle)
        }
        stateHolder.clearPausedFlags()
        sessionJob = parentScope.launch {
            SessionRunner(
                sessionScope = this,
                stateHolder = stateHolder,
                serverLauncher = serverLauncher,
                transport = transport,
                config = config,
                clock = clock,
                frameConsumer = frameConsumer,
                nonceGenerator = nonceGenerator,
                lifecycleHooks = lifecycleHooks
            ).run()
        }
    }

    /** Moves the session to [SessionState.Paused], notifies the server, and releases the surface. */
    suspend fun pause() {
        pauseMutex.withLock {
            val streaming = stateHolder.current as? SessionState.Streaming ?: return
            lifecycleHooks.onBeforePause()
            surfaceController.releaseSurface()
            stateHolder.set(
                SessionState.Paused(
                    endpoint = streaming.endpoint,
                    negotiatedVersion = streaming.negotiatedVersion,
                    negotiatedFeatures = streaming.negotiatedFeatures,
                    deviceInfo = streaming.deviceInfo,
                    videoSize = streaming.videoSize,
                    surfaceReleased = true
                )
            )
            try {
                transport.writeControl(ControlCommand.Pause)
            } catch (_: IOException) {
                stateHolder.markPausedLinkLost()
                withContext(NonCancellable) { transport.close() }
            }
        }
    }

    /** Returns from [SessionState.Paused] to [SessionState.Streaming] or rediscovers if link died. */
    suspend fun resume() {
        pauseMutex.withLock {
            val paused = stateHolder.current as? SessionState.Paused ?: return
            if (stateHolder.isPausedLinkLost) {
                stateHolder.triggerPausedResume()
                return
            }
            try {
                transport.writeControl(ControlCommand.Resume)
                transport.writeControl(ControlCommand.KeyframeRequest)
                stateHolder.set(
                    SessionState.Streaming(
                        endpoint = paused.endpoint,
                        negotiatedVersion = paused.negotiatedVersion,
                        negotiatedFeatures = paused.negotiatedFeatures,
                        deviceInfo = paused.deviceInfo,
                        videoSize = paused.videoSize
                    )
                )
            } catch (_: IOException) {
                stateHolder.markPausedLinkLost()
                withContext(NonCancellable) { transport.close() }
                stateHolder.triggerPausedResume()
            }
        }
    }

    /** Sends an in-session control packet while [SessionState.Streaming]. */
    suspend fun sendControl(command: ControlCommand) {
        if (stateHolder.current !is SessionState.Streaming) {
            throw IOException("Cannot send $command while state is ${stateHolder.current}")
        }
        transport.writeControl(command)
    }

    /** Skips the remaining backoff delay while [SessionState.Reconnecting]. */
    fun retryNow() {
        stateHolder.triggerRetryNow()
    }

    /** Gracefully stops the active session, sends `BYE` if streaming, and returns to [SessionState.Idle]. */
    suspend fun disconnect() {
        lifecycleMutex.withLock {
            val current = stateHolder.current
            if (current is SessionState.Streaming || current is SessionState.Paused) {
                lifecycleHooks.onBeforeDisconnect()
                try {
                    transport.writeControl(ControlCommand.Bye(null))
                } catch (_: IOException) {
                    // Best-effort BYE before closing.
                }
            }
            val active = sessionJob
            sessionJob = null
            stateHolder.cancelWaiters()
            active?.cancel()
            withContext(NonCancellable) {
                active?.join()
                transport.close()
                serverLauncher.stop()
            }
            lifecycleHooks.onSessionEnded()
            stateHolder.clearPausedFlags()
            stateHolder.set(SessionState.Idle)
        }
    }

    /** Transitions an active session into [SessionState.Failed] after an unrecoverable error. */
    suspend fun failWith(error: SessionError) {
        lifecycleMutex.withLock {
            val active = sessionJob
            sessionJob = null
            stateHolder.cancelWaiters()
            active?.cancel()
            withContext(NonCancellable) {
                active?.join()
                transport.close()
                serverLauncher.stop()
            }
            lifecycleHooks.onSessionEnded()
            stateHolder.clearPausedFlags()
            stateHolder.set(SessionState.Failed(error))
        }
    }

    /** Cancels all engine-owned coroutines immediately. */
    fun close() {
        closed.set(true)
        stateHolder.cancelWaiters()
        sessionJob?.cancel()
        sessionJob = null
    }

    private companion object {
        val secureRandom = SecureRandom()
        val defaultNonceGenerator: () -> ByteArray = {
            ByteArray(ProtocolV2.NONCE_SIZE).also { secureRandom.nextBytes(it) }
        }
    }
}

internal class SessionStateHolder {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Idle)
    val flow: StateFlow<SessionState> = mutableState.asStateFlow()
    val current: SessionState get() = mutableState.value

    private val retrySignal = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pausedResumeSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile
    var isPausedLinkLost: Boolean = false
        private set

    @Volatile
    private var resumeRequested: Boolean = false

    fun set(newState: SessionState) {
        mutableState.value = newState
    }

    fun updateStreaming(transform: (SessionState.Streaming) -> SessionState.Streaming) {
        val value = mutableState.value
        if (value is SessionState.Streaming) {
            mutableState.value = transform(value)
        }
    }

    fun updatePaused(transform: (SessionState.Paused) -> SessionState.Paused) {
        val value = mutableState.value
        if (value is SessionState.Paused) {
            mutableState.value = transform(value)
        }
    }

    fun markPausedLinkLost() {
        isPausedLinkLost = true
    }

    fun clearPausedFlags() {
        isPausedLinkLost = false
        resumeRequested = false
    }

    fun triggerPausedResume() {
        resumeRequested = true
        pausedResumeSignal.getAndSet(null)?.complete(Unit)
    }

    fun triggerRetryNow() {
        retrySignal.getAndSet(null)?.complete(Unit)
    }

    fun cancelWaiters() {
        retrySignal.getAndSet(null)?.cancel()
        pausedResumeSignal.getAndSet(null)?.cancel()
    }

    suspend fun awaitResumeFromPause() {
        val deferred = CompletableDeferred<Unit>()
        pausedResumeSignal.set(deferred)
        try {
            if (!resumeRequested) {
                deferred.await()
            }
        } finally {
            pausedResumeSignal.compareAndSet(deferred, null)
        }
    }

    suspend fun waitBackoffDelay(delayMs: Long) {
        val deferred = CompletableDeferred<Unit>()
        retrySignal.set(deferred)
        try {
            withTimeoutOrNull(delayMs) { deferred.await() }
        } finally {
            retrySignal.compareAndSet(deferred, null)
        }
    }
}

private class ActiveSessionSetup(
    val endpoint: ServerEndpoint,
    val handshake: HandshakeResult,
    val connectionJob: Job
) {
    suspend fun closeConnection(transport: StreamTransport) {
        withContext(NonCancellable) {
            transport.close()
            connectionJob.cancel()
            connectionJob.join()
        }
    }
}

private class SessionRunner(
    private val sessionScope: CoroutineScope,
    private val stateHolder: SessionStateHolder,
    private val serverLauncher: ServerLauncher,
    private val transport: StreamTransport,
    private val config: SessionConfig,
    private val clock: SessionClock,
    private val frameConsumer: SessionFrameConsumer,
    private val nonceGenerator: () -> ByteArray,
    private val lifecycleHooks: SessionLifecycleHooks
) {
    suspend fun run() {
        var activeSetup = establishInitialSession()
        while (activeSetup != null) {
            stateHolder.clearPausedFlags()
            stateHolder.set(
                SessionState.Streaming(
                    endpoint = activeSetup.endpoint,
                    negotiatedVersion = activeSetup.handshake.negotiatedVersion,
                    negotiatedFeatures = activeSetup.handshake.negotiatedFeatures
                )
            )
            val endReason = ActiveSessionMonitor(
                stateHolder = stateHolder,
                transport = transport,
                config = config,
                clock = clock,
                frameConsumer = frameConsumer
            ).run()
            activeSetup.closeConnection(transport)
            activeSetup = handleSessionEnd(activeSetup.endpoint, endReason)
        }
        lifecycleHooks.onSessionEnded()
    }

    private suspend fun establishInitialSession(): ActiveSessionSetup? {
        val endpoint = launchServer() ?: return null
        stateHolder.set(SessionState.Handshaking(endpoint, attempt = 1))
        return when (val outcome = connectAndHandshake(endpoint)) {
            is HandshakeOutcome.Success -> outcome.setup

            is HandshakeOutcome.Fatal -> {
                stateHolder.set(SessionState.Failed(outcome.error))
                null
            }

            is HandshakeOutcome.Transient -> reconnect(endpoint, outcome.error)
        }
    }

    private suspend fun handleSessionEnd(
        endpoint: ServerEndpoint,
        endReason: SessionEndReason
    ): ActiveSessionSetup? = when (endReason) {
        SessionEndReason.CleanBye -> {
            stateHolder.set(SessionState.Idle)
            null
        }

        is SessionEndReason.TerminalFailure -> {
            stateHolder.set(SessionState.Failed(endReason.error))
            null
        }

        is SessionEndReason.RecoverableLoss -> {
            lifecycleHooks.onConnectionLost()
            if (stateHolder.current is SessionState.Paused) {
                handlePausedLinkLoss()
            } else {
                reconnect(endpoint, endReason.error)
            }
        }
    }

    private suspend fun handlePausedLinkLoss(): ActiveSessionSetup? {
        stateHolder.markPausedLinkLost()
        stateHolder.awaitResumeFromPause()
        stateHolder.clearPausedFlags()
        return establishInitialSession()
    }

    private suspend fun reconnect(
        initialEndpoint: ServerEndpoint,
        initialError: SessionError
    ): ActiveSessionSetup? {
        val startMs = clock.monotonicMillis()
        var currentEndpoint = initialEndpoint
        var lastError = initialError
        var attempt = 1
        var connected: ActiveSessionSetup? = null
        var done = false

        while (!done) {
            val elapsedMs = clock.monotonicMillis() - startMs
            val delayMs = config.reconnect.delayForAttempt(attempt)
            val exhausted =
                elapsedMs >= config.reconnect.maxDurationMs ||
                    elapsedMs + delayMs > config.reconnect.maxDurationMs
            if (exhausted) {
                stateHolder.set(SessionState.Failed(lastError))
                done = true
            } else {
                stateHolder.set(
                    SessionState.Reconnecting(
                        attempt = attempt,
                        nextRetryDelayMs = delayMs,
                        elapsedMs = elapsedMs,
                        maxDurationMs = config.reconnect.maxDurationMs,
                        lastError = lastError,
                        relaunchingServer = false
                    )
                )
                stateHolder.waitBackoffDelay(delayMs)

                when (val result = attemptReconnectCycle(currentEndpoint, attempt)) {
                    is ReconnectOutcome.Connected -> {
                        connected = result.setup
                        done = true
                    }

                    is ReconnectOutcome.Fatal -> {
                        stateHolder.set(SessionState.Failed(result.error))
                        done = true
                    }

                    is ReconnectOutcome.Retry -> {
                        currentEndpoint = result.endpoint
                        lastError = result.error
                        attempt += 1
                    }
                }
            }
        }
        return connected
    }

    private suspend fun attemptReconnectCycle(
        cachedEndpoint: ServerEndpoint,
        attempt: Int
    ): ReconnectOutcome {
        stateHolder.set(SessionState.Handshaking(cachedEndpoint, attempt))
        return when (val firstTry = connectAndHandshake(cachedEndpoint)) {
            is HandshakeOutcome.Success -> ReconnectOutcome.Connected(firstTry.setup)
            is HandshakeOutcome.Fatal -> ReconnectOutcome.Fatal(firstTry.error)
            is HandshakeOutcome.Transient -> relaunchAndHandshake(cachedEndpoint, attempt)
        }
    }

    private suspend fun relaunchAndHandshake(
        fallbackEndpoint: ServerEndpoint,
        attempt: Int
    ): ReconnectOutcome = try {
        val newEndpoint = launchServerOrThrow()
        stateHolder.set(SessionState.Handshaking(newEndpoint, attempt))
        when (val secondTry = connectAndHandshake(newEndpoint)) {
            is HandshakeOutcome.Success -> ReconnectOutcome.Connected(secondTry.setup)

            is HandshakeOutcome.Fatal -> ReconnectOutcome.Fatal(secondTry.error)

            is HandshakeOutcome.Transient ->
                ReconnectOutcome.Retry(newEndpoint, secondTry.error)
        }
    } catch (e: SessionErrorException) {
        if (e.error.isRetryable) {
            ReconnectOutcome.Retry(fallbackEndpoint, e.error)
        } else {
            ReconnectOutcome.Fatal(e.error)
        }
    } catch (e: IOException) {
        ReconnectOutcome.Retry(
            fallbackEndpoint,
            SessionError.ServerStartFailed(e.message)
        )
    }

    private suspend fun launchServer(): ServerEndpoint? = try {
        launchServerOrThrow()
    } catch (e: SessionErrorException) {
        stateHolder.set(SessionState.Failed(e.error))
        null
    } catch (e: IOException) {
        stateHolder.set(SessionState.Failed(SessionError.ServerStartFailed(e.message)))
        null
    }

    private suspend fun launchServerOrThrow(): ServerEndpoint {
        stateHolder.set(SessionState.Discovering(config.targetId))
        return serverLauncher.start { phase ->
            val next = when (phase) {
                LaunchPhase.DISCOVERING -> SessionState.Discovering(config.targetId)
                LaunchPhase.CONNECTING -> SessionState.Connecting(config.targetId)
                LaunchPhase.STARTING_SERVER -> SessionState.StartingServer(config.targetId)
            }
            stateHolder.set(next)
        }
    }

    private suspend fun connectAndHandshake(endpoint: ServerEndpoint): HandshakeOutcome {
        val connectionJob = Job(sessionScope.coroutineContext[Job])
        val connectionScope = CoroutineScope(sessionScope.coroutineContext + connectionJob)
        return try {
            transport.connect(endpoint, config.socketReadTimeoutMs, connectionScope)
            val result = SessionHandshakeRunner.execute(
                transport = transport,
                endpoint = endpoint,
                config = config,
                nonceGenerator = nonceGenerator
            )
            HandshakeOutcome.Success(ActiveSessionSetup(endpoint, result, connectionJob))
        } catch (e: SessionErrorException) {
            cleanupFailedConnection(connectionJob)
            if (e.error.isRetryable) {
                HandshakeOutcome.Transient(e.error)
            } else {
                HandshakeOutcome.Fatal(e.error)
            }
        } catch (e: ProtocolException) {
            cleanupFailedConnection(connectionJob)
            HandshakeOutcome.Fatal(SessionError.fromErrorCode(e.code, e.message))
        } catch (e: IOException) {
            cleanupFailedConnection(connectionJob)
            HandshakeOutcome.Transient(SessionError.LinkLost(e.message))
        } catch (e: CancellationException) {
            cleanupFailedConnection(connectionJob)
            throw e
        }
    }

    private suspend fun cleanupFailedConnection(connectionJob: Job) {
        withContext(NonCancellable) {
            transport.close()
            connectionJob.cancel()
            connectionJob.join()
        }
    }

    private sealed interface HandshakeOutcome {
        data class Success(val setup: ActiveSessionSetup) : HandshakeOutcome
        data class Fatal(val error: SessionError) : HandshakeOutcome
        data class Transient(val error: SessionError) : HandshakeOutcome
    }

    private sealed interface ReconnectOutcome {
        data class Connected(val setup: ActiveSessionSetup) : ReconnectOutcome
        data class Fatal(val error: SessionError) : ReconnectOutcome
        data class Retry(val endpoint: ServerEndpoint, val error: SessionError) : ReconnectOutcome
    }
}
