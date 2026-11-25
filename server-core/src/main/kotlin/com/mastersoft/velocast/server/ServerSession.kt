package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.AuthProof
import com.mastersoft.velocast.protocol.Authenticator
import com.mastersoft.velocast.protocol.Bye
import com.mastersoft.velocast.protocol.ClipboardMessage
import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.DeviceAction
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.ErrorMessage
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.Pong
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.SeqTracker
import com.mastersoft.velocast.protocol.ServerHandshake
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.TextInput
import com.mastersoft.velocast.protocol.TouchAction
import com.mastersoft.velocast.server.platform.DeviceStateController
import com.mastersoft.velocast.server.platform.SavedDeviceState
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class SessionState {
    Handshaking,
    Active,
    Closing
}

/**
 * Single owner of a client connection's lifecycle and resources (sockets, protocol reader/writer,
 * [InputInjector], [MediaPipeline], rotation watcher, and [CleanUpGuardian]).
 */
class ServerSession(
    val id: Long,
    val peerSocket: PeerSocket,
    private val authenticator: Authenticator,
    private val environment: SessionEnvironment,
    private val clock: ServerClock,
    private val logger: Logger,
    private val handshakeTimer: HandshakeTimer,
    private val handshakeTimeoutMs: Long,
    private val sessionIdleTimeoutMs: Long,
    private val random: SecureRandom,
    private val onActivate: (ServerSession) -> Boolean,
    private val onAuthFailed: (InetAddress) -> Unit,
    private val onClosed: (ServerSession) -> Unit
) {
    private val stateLock = Any()

    @Volatile
    var state: SessionState = SessionState.Handshaking
        private set

    private val closedLatch = CountDownLatch(1)
    private val authFailureRecorded = AtomicBoolean(false)
    private val controlReader = ControlReader(peerSocket.inputStream)
    private val frameWriter = FrameWriter(peerSocket.outputStream)
    private val frameSink = SessionFrameSink(frameWriter, peerSocket.outputStream)
    private val seqTracker = SeqTracker()
    private val packet = ControlPacket()

    private var handshakeTimeoutHandle: AutoCloseable? = null
    private var inputInjector: InputInjector? = null
    private var mediaPipeline: MediaPipeline? = null
    private var rotationWatcherHandle: Closeable? = null
    private var stateController: DeviceStateController? = null
    private var initialDeviceState: SavedDeviceState? = null
    private var cleanUpGuardian: CleanUpGuardian? = null

    fun run() {
        try {
            val established = performHandshake()
            if (established && state == SessionState.Active) {
                runActiveSession()
            }
        } finally {
            cleanupResources()
        }
    }

    fun requestClose() {
        val timerToCancel = synchronized(stateLock) {
            if (state == SessionState.Closing) return
            state = SessionState.Closing
            handshakeTimeoutHandle.also { handshakeTimeoutHandle = null }
        }
        SessionIoHelpers.closeQuietly(timerToCancel, "handshake timer", id, logger)
        SessionIoHelpers.closeQuietly(peerSocket, "peer socket", id, logger)
    }

    fun closeAndAwait() {
        requestClose()
        try {
            closedLatch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(TAG, "Interrupted awaiting session $id close", e)
        }
    }

    internal fun markActiveFromGuard(): Boolean = synchronized(stateLock) {
        if (state != SessionState.Handshaking) {
            false
        } else {
            state = SessionState.Active
            true
        }
    }

    private fun performHandshake(): Boolean {
        val serverNonce = ByteArray(ProtocolV2.NONCE_SIZE).also { random.nextBytes(it) }
        val handshake = ServerHandshake(authenticator, environment.capabilities, serverNonce)
        val deadlineMs = clock.monotonicMillis() + handshakeTimeoutMs
        val failOnce = {
            SessionIoHelpers.recordFailureOnce(
                authFailureRecorded,
                peerSocket.remoteAddress,
                onAuthFailed
            )
        }

        val timer = handshakeTimer.schedule(handshakeTimeoutMs) {
            val shouldFail = synchronized(stateLock) { state == SessionState.Handshaking }
            if (shouldFail) {
                logger.warn(TAG, "Session $id handshake deadline (${handshakeTimeoutMs}ms) expired")
                failOnce()
                requestClose()
            }
        }
        synchronized(stateLock) {
            if (state == SessionState.Closing) {
                SessionIoHelpers.closeQuietly(timer, "handshake timer", id, logger)
                return false
            }
            handshakeTimeoutHandle = timer
        }

        return try {
            executeHandshakeExchange(handshake, deadlineMs)
        } catch (e: ProtocolException) {
            logger.warn(TAG, "Session $id handshake rejected (${e.code})", e)
            failOnce()
            SessionIoHelpers.sendErrorQuietly(
                frameSink,
                e.code,
                e.message ?: e.code.name,
                id,
                logger
            )
            requestClose()
            false
        } catch (e: SocketTimeoutException) {
            logger.warn(TAG, "Session $id handshake timed out", e)
            failOnce()
            requestClose()
            false
        } catch (e: IOException) {
            if (state != SessionState.Closing) {
                logger.warn(TAG, "Session $id handshake I/O error", e)
                failOnce()
            }
            requestClose()
            false
        } finally {
            cancelHandshakeTimer()
        }
    }

    private fun executeHandshakeExchange(handshake: ServerHandshake, deadlineMs: Long): Boolean {
        var serverProof: AuthProof? = null
        val helloOk = SessionIoHelpers.readExpectedHandshakeMessage(
            peerSocket = peerSocket,
            controlReader = controlReader,
            packet = packet,
            expectedType = MessageType.HELLO,
            clock = clock,
            deadlineMs = deadlineMs,
            onCleanEof = {
                SessionIoHelpers.recordFailureOnce(
                    authFailureRecorded,
                    peerSocket.remoteAddress,
                    onAuthFailed
                )
            },
            onBye = { SessionIoHelpers.sendByeQuietly(frameSink, null, id, logger) }
        )
        if (helloOk) {
            val serverHello = handshake.onHello(controlReader.payload, 0, packet.payloadLength)
            frameSink.writeMessage(serverHello)
            val proofOk = SessionIoHelpers.readExpectedHandshakeMessage(
                peerSocket = peerSocket,
                controlReader = controlReader,
                packet = packet,
                expectedType = MessageType.AUTH_PROOF,
                clock = clock,
                deadlineMs = deadlineMs,
                onCleanEof = {
                    throw ProtocolException(ErrorCode.TRUNCATED, "Stream ended before AUTH_PROOF")
                },
                onBye = { SessionIoHelpers.sendByeQuietly(frameSink, null, id, logger) }
            )
            if (proofOk) {
                serverProof =
                    handshake.onClientProof(controlReader.payload, 0, packet.payloadLength)
            }
        }
        cancelHandshakeTimer()

        var established = false
        if (serverProof != null) {
            if (onActivate(this)) {
                frameSink.writeMessage(serverProof)
                controlReader.phase = Phase.SESSION
                frameSink.activateSession(
                    sessionId = id,
                    logger = logger,
                    onWriteError = { requestClose() }
                )
                established = true
            } else {
                SessionIoHelpers.sendByeQuietly(frameSink, null, id, logger)
            }
        }
        return established
    }

    private fun runActiveSession() {
        try {
            peerSocket.setSoTimeout(sessionIdleTimeoutMs.toInt())
            val deviceInfo = environment.queryDeviceInfo()
            frameSink.enqueuePriority(deviceInfo)

            val resources = setupActiveResources(deviceInfo.display.rotation, deviceInfo.display)
                ?: return

            SessionCommandHelpers.reportInitialCapabilities(
                environment = environment,
                injector = resources.injector,
                hasRotationWatcher = resources.watcher != null,
                savedState = resources.savedState,
                frameSink = frameSink
            )
            resources.pipeline.start()
            logger.info(TAG, "Session $id active for ${peerSocket.remoteAddress.hostAddress}")
            readSessionCommands(resources.injector, resources.pipeline, resources.controller)
        } catch (e: ProtocolException) {
            logger.warn(TAG, "Session $id protocol violation: ${e.code}", e)
            SessionIoHelpers.sendErrorQuietly(
                frameSink,
                e.code,
                e.message ?: e.code.name,
                id,
                logger
            )
        } catch (e: UnsupportedPlatformException) {
            logger.error(TAG, "Session $id platform capability unsupported", e)
            SessionIoHelpers.sendErrorQuietly(
                frameSink,
                ErrorCode.SERVER_START_FAILED,
                e.message ?: "Unsupported platform",
                id,
                logger
            )
        } catch (e: SocketTimeoutException) {
            logger.info(TAG, "Session $id idle timeout (${sessionIdleTimeoutMs}ms)", e)
        } catch (e: IOException) {
            if (state != SessionState.Closing) {
                logger.info(TAG, "Session $id connection ended", e)
            }
        }
    }

    private fun setupActiveResources(
        initialRotation: Int,
        display: com.mastersoft.velocast.protocol.DisplayGeometry
    ): ActiveResources? {
        val injector = environment.createInjector(display)
        val controller = environment.createStateController()
        val savedState = controller?.captureState()
        val guardian = if (savedState != null) {
            environment.startCleanUpGuardian(savedState, controller.displayPowerSupported)
        } else {
            null
        }
        val lastRotation = AtomicInteger(initialRotation)
        val pipeline = environment.createMediaPipeline(display, frameSink) { geom ->
            injector.updateDisplaySize(geom.width, geom.height)
            if (lastRotation.getAndSet(geom.rotation) != geom.rotation) {
                frameSink.enqueuePriority(Status(StatusKind.ROTATION, geom.rotation))
            }
        }
        val watcher = environment.registerRotationWatcher { pipeline.notifyRotationChanged() }
        frameSink.setOnFrameDropped { pipeline.requestKeyframe() }

        val installed = synchronized(stateLock) {
            if (state != SessionState.Active) {
                false
            } else {
                inputInjector = injector
                mediaPipeline = pipeline
                rotationWatcherHandle = watcher
                stateController = controller
                initialDeviceState = savedState
                cleanUpGuardian = guardian
                true
            }
        }
        if (!installed) {
            SessionIoHelpers.closeQuietly(watcher, "rotation watcher", id, logger)
            SessionIoHelpers.closeQuietly(pipeline, "media pipeline", id, logger)
            SessionIoHelpers.disarmGuardianQuietly(guardian, id, logger)
            return null
        }
        return ActiveResources(injector, pipeline, watcher, controller, savedState)
    }

    private fun readSessionCommands(
        injector: InputInjector,
        pipeline: MediaPipeline,
        controller: DeviceStateController?
    ) {
        val dispatcher = SessionCommandDispatcher(
            sessionId = id,
            controlReader = controlReader,
            packet = packet,
            frameSink = frameSink,
            clock = clock,
            logger = logger
        )
        var keepReading = true
        while (keepReading && state == SessionState.Active && !peerSocket.isClosed) {
            val hasNext = controlReader.read(packet)
            if (!hasNext) {
                keepReading = false
            } else {
                seqTracker.onSeq(packet.seq)
                keepReading = dispatcher.dispatch(injector, pipeline, controller)
            }
        }
    }

    private fun cancelHandshakeTimer() {
        val timer = synchronized(stateLock) {
            handshakeTimeoutHandle.also { handshakeTimeoutHandle = null }
        }
        SessionIoHelpers.closeQuietly(timer, "handshake timer", id, logger)
    }

    private fun cleanupResources() {
        val timer: AutoCloseable?
        val injector: InputInjector?
        val pipeline: MediaPipeline?
        val watcher: Closeable?
        val controller: DeviceStateController?
        val savedState: SavedDeviceState?
        val guardian: CleanUpGuardian?
        synchronized(stateLock) {
            state = SessionState.Closing
            timer = handshakeTimeoutHandle.also { handshakeTimeoutHandle = null }
            injector = inputInjector.also { inputInjector = null }
            pipeline = mediaPipeline.also { mediaPipeline = null }
            watcher = rotationWatcherHandle.also { rotationWatcherHandle = null }
            controller = stateController.also { stateController = null }
            savedState = initialDeviceState.also { initialDeviceState = null }
            guardian = cleanUpGuardian.also { cleanUpGuardian = null }
        }
        SessionIoHelpers.closeQuietly(timer, "handshake timer", id, logger)
        if (injector != null) {
            try {
                injector.cancelAllPointers()
            } catch (e: IllegalStateException) {
                logger.warn(TAG, "Error cancelling pointers on session $id teardown", e)
            } catch (e: InjectionDeniedException) {
                logger.debug(TAG, "Pointer cancel denied on session $id teardown", e)
            }
        }
        SessionIoHelpers.closeQuietly(watcher, "rotation watcher", id, logger)
        SessionIoHelpers.closeQuietly(pipeline, "media pipeline", id, logger)
        SessionIoHelpers.restoreDeviceState(controller, savedState, guardian, id, logger)
        frameSink.flushAndClose()
        SessionIoHelpers.closeQuietly(peerSocket, "peer socket", id, logger)
        onClosed(this)
        closedLatch.countDown()
    }

    private class ActiveResources(
        val injector: InputInjector,
        val pipeline: MediaPipeline,
        val watcher: Closeable?,
        val controller: DeviceStateController?,
        val savedState: SavedDeviceState?
    )

    private companion object {
        const val TAG = "ServerSession"
    }
}

private class SessionCommandDispatcher(
    private val sessionId: Long,
    private val controlReader: ControlReader,
    private val packet: ControlPacket,
    private val frameSink: SessionFrameSink,
    private val clock: ServerClock,
    private val logger: Logger
) {
    private var injectionDeniedDuringMove = false

    fun dispatch(
        injector: InputInjector,
        pipeline: MediaPipeline,
        controller: DeviceStateController?
    ): Boolean {
        when (packet.type) {
            MessageType.TOUCH -> dispatchTouch(injector)

            MessageType.KEY -> runWithInjectionGuard(isMove = false) {
                injector.injectKey(packet.param, packet.keyAction())
            }

            MessageType.TEXT -> {
                val textInput = TextInput.decode(controlReader.payload, 0, packet.payloadLength)
                logger.debug(
                    TAG,
                    "Session $sessionId received TEXT (${textInput.text.length} chars)"
                )
            }

            MessageType.CLIPBOARD -> {
                val clip = ClipboardMessage.decode(controlReader.payload, 0, packet.payloadLength)
                logger.debug(TAG, "Session $sessionId received CLIPBOARD (paste=${clip.paste})")
            }

            MessageType.CONFIG_STREAM -> {
                pipeline.reconfigure(packet.param, packet.x.toInt(), packet.y)
            }

            MessageType.KEYFRAME_REQ -> pipeline.requestKeyframe()

            MessageType.PAUSE -> {
                injector.cancelAllPointers()
                pipeline.pause()
                frameSink.enqueuePriority(Status(StatusKind.PAUSED, 1))
            }

            MessageType.RESUME -> {
                pipeline.resume()
                frameSink.enqueuePriority(Status(StatusKind.PAUSED, 0))
            }

            MessageType.ACTION -> dispatchAction(injector, controller)

            MessageType.PING -> frameSink.enqueuePriority(
                Pong(packet.param, clock.monotonicMicros())
            )

            MessageType.BYE -> {
                logger.info(TAG, "Session $sessionId received BYE (code=${packet.param})")
                SessionIoHelpers.sendByeQuietly(frameSink, null, sessionId, logger)
                return false
            }

            else -> {
                throw ProtocolException(
                    ErrorCode.UNEXPECTED_MESSAGE,
                    "Unexpected message type ${packet.type} in session phase"
                )
            }
        }
        return true
    }

    private fun dispatchTouch(injector: InputInjector) {
        val action = packet.touchAction()
        if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            injectionDeniedDuringMove = false
        }
        runWithInjectionGuard(isMove = action == TouchAction.MOVE) {
            injector.handleTouch(packet.pointer, action, packet.x, packet.y)
        }
    }

    private fun dispatchAction(injector: InputInjector, controller: DeviceStateController?) {
        val action = packet.deviceAction()
        if (action == DeviceAction.DISPLAY_POWER_ON || action == DeviceAction.DISPLAY_POWER_OFF) {
            val turnOn = action == DeviceAction.DISPLAY_POWER_ON
            val ok = controller != null &&
                controller.displayPowerSupported &&
                controller.setDisplayPowerOn(turnOn)
            if (ok) {
                frameSink.enqueuePriority(Status(StatusKind.DISPLAY_POWER, if (turnOn) 1 else 0))
            } else {
                frameSink.enqueuePriority(
                    Status(StatusKind.DEGRADED, BIT_DISPLAY_POWER, "display_power")
                )
            }
        } else {
            runWithInjectionGuard(isMove = false) {
                injector.performAction(action)
            }
        }
    }

    private inline fun runWithInjectionGuard(isMove: Boolean, block: () -> Unit) {
        try {
            block()
        } catch (e: InjectionDeniedException) {
            if (!isMove || !injectionDeniedDuringMove) {
                if (isMove) injectionDeniedDuringMove = true
                logger.warn(TAG, "Session $sessionId input injection denied", e)
                val detail = SessionIoHelpers.truncateErrorDetail(
                    e.message ?: "Input injection denied"
                )
                frameSink.enqueuePriority(ErrorMessage(ErrorCode.INJECTION_DENIED, false, detail))
            }
        }
    }

    private companion object {
        const val TAG = "ServerSession"
        const val BIT_DISPLAY_POWER = 3
    }
}

private object SessionCommandHelpers {
    fun reportInitialCapabilities(
        environment: SessionEnvironment,
        injector: InputInjector,
        hasRotationWatcher: Boolean,
        savedState: SavedDeviceState?,
        frameSink: SessionFrameSink
    ) {
        val caps = environment.probeCapabilities(injector, hasRotationWatcher)
        val powerVal = if (savedState?.displayPowerOn != false) 1 else 0
        frameSink.enqueuePriority(
            Status(StatusKind.DISPLAY_POWER, powerVal, caps.toStatusDetail())
        )
        for (degraded in caps.degradedStatuses()) {
            frameSink.enqueuePriority(degraded)
        }
    }
}

private object SessionIoHelpers {
    private const val TAG = "ServerSession"

    fun recordFailureOnce(
        recorded: AtomicBoolean,
        address: InetAddress,
        onAuthFailed: (InetAddress) -> Unit
    ) {
        if (recorded.compareAndSet(false, true)) {
            onAuthFailed(address)
        }
    }

    fun readExpectedHandshakeMessage(
        peerSocket: PeerSocket,
        controlReader: ControlReader,
        packet: ControlPacket,
        expectedType: MessageType,
        clock: ServerClock,
        deadlineMs: Long,
        onCleanEof: () -> Unit,
        onBye: () -> Unit
    ): Boolean {
        applyRemainingTimeout(peerSocket, clock, deadlineMs)
        var accepted = false
        if (!controlReader.read(packet)) {
            onCleanEof()
        } else if (packet.type == MessageType.BYE) {
            onBye()
        } else if (packet.type != expectedType) {
            throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Expected $expectedType")
        } else {
            accepted = true
        }
        return accepted
    }

    private fun applyRemainingTimeout(
        peerSocket: PeerSocket,
        clock: ServerClock,
        deadlineMs: Long
    ) {
        val remaining = deadlineMs - clock.monotonicMillis()
        if (remaining <= 0L) {
            throw SocketTimeoutException("Handshake deadline exceeded")
        }
        peerSocket.setSoTimeout(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    fun sendErrorQuietly(
        frameSink: SessionFrameSink,
        code: ErrorCode,
        detail: String,
        sessionId: Long,
        logger: Logger
    ) {
        try {
            val safeDetail = truncateErrorDetail(detail)
            frameSink.writeMessage(ErrorMessage(code, true, safeDetail))
        } catch (e: IOException) {
            logger.debug(TAG, "Failed sending ERROR ($code) on session $sessionId", e)
        } catch (e: ProtocolException) {
            logger.warn(TAG, "Failed formatting ERROR ($code) on session $sessionId", e)
        }
    }

    fun sendByeQuietly(
        frameSink: SessionFrameSink,
        code: ErrorCode?,
        sessionId: Long,
        logger: Logger
    ) {
        try {
            frameSink.writeMessage(Bye(code))
        } catch (e: IOException) {
            logger.debug(TAG, "Failed sending BYE on session $sessionId", e)
        }
    }

    fun restoreDeviceState(
        controller: DeviceStateController?,
        savedState: SavedDeviceState?,
        guardian: CleanUpGuardian?,
        sessionId: Long,
        logger: Logger
    ) {
        if (controller != null && savedState != null) {
            try {
                controller.restoreState(savedState)
            } catch (e: SecurityException) {
                logger.warn(TAG, "Failed restoring device state on session $sessionId teardown", e)
            } catch (e: IllegalStateException) {
                logger.warn(TAG, "Failed restoring device state on session $sessionId teardown", e)
            }
        }
        disarmGuardianQuietly(guardian, sessionId, logger)
    }

    fun disarmGuardianQuietly(guardian: CleanUpGuardian?, sessionId: Long, logger: Logger) {
        if (guardian == null) return
        try {
            guardian.disarm()
            guardian.close()
        } catch (e: IOException) {
            logger.warn(TAG, "Error disarming CleanUp guardian on session $sessionId", e)
        }
    }

    fun closeQuietly(resource: AutoCloseable?, label: String, sessionId: Long, logger: Logger) {
        if (resource == null) return
        try {
            resource.close()
        } catch (e: IOException) {
            logger.debug(TAG, "Error closing $label on session $sessionId", e)
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Error closing $label on session $sessionId", e)
        }
    }

    fun truncateErrorDetail(detail: String): String {
        val clean = detail.replace("\u0000", "")
        val max = ProtocolV2.MAX_ERROR_DETAIL_BYTES
        if (clean.toByteArray(Charsets.UTF_8).size <= max) return clean
        val sb = StringBuilder()
        var bytes = 0
        for (ch in clean) {
            val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > max) break
            sb.append(ch)
            bytes += charBytes
        }
        return sb.toString()
    }
}
