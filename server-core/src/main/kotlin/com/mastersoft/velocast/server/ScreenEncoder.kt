package com.mastersoft.velocast.server

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.Validators
import com.mastersoft.velocast.protocol.VideoSize
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Abstraction over display geometry queries and mirror surface capture so [ScreenEncoder] can be
 * verified in both unit tests and unprivileged instrumentation tests.
 */
interface CaptureBackend {
    fun queryDisplayGeometry(): DisplayGeometry
    fun startCapture(surface: Surface, width: Int, height: Int): Closeable
}

class VirtualDisplayCaptureBackend(private val logger: Logger) : CaptureBackend {
    override fun queryDisplayGeometry(): DisplayGeometry =
        VirtualDisplayBridge(logger).queryDefaultDisplayGeometry()

    override fun startCapture(surface: Surface, width: Int, height: Int): Closeable {
        val bridge = VirtualDisplayBridge(logger)
        bridge.startMirroring(surface, width, height)
        return bridge
    }
}

/** Initial stream options for [ScreenEncoder]. */
data class StreamOptions(
    val bitrateBps: Int = DEFAULT_BITRATE_BPS,
    val fps: Int = DEFAULT_FPS,
    val resolutionScale: Float = DEFAULT_SCALE,
    val bitrateMode: BitrateMode = BitrateMode.DEFAULT
) {
    init {
        Validators.bitrateBps(bitrateBps)
        Validators.fps(fps.toFloat())
        Validators.scale(resolutionScale)
    }

    companion object {
        const val DEFAULT_BITRATE_BPS = 8_000_000
        const val DEFAULT_FPS = 60
        const val DEFAULT_SCALE = 1.0f
    }
}

/**
 * Hardware H.264/AVC encoder pipeline backed by `MediaCodec`, [CaptureBackend], and non-blocking
 * [SessionFrameSink] (FUN-01, FUN-02, PERF-01, PERF-04, PERF-08, PERF-09).
 */
class ScreenEncoder(
    initialDisplay: DisplayGeometry,
    private val frameSink: SessionFrameSink,
    private val clock: ServerClock,
    private val logger: Logger,
    options: StreamOptions = StreamOptions(),
    private val captureBackend: CaptureBackend = VirtualDisplayCaptureBackend(logger),
    private val onGeometryChanged: (DisplayGeometry) -> Unit = {}
) : MediaPipeline {
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private var displayGeometry: DisplayGeometry = initialDisplay
    private var bitrateBps: Int = options.bitrateBps
    private var fps: Int = options.fps
    private var resolutionScale: Float = options.resolutionScale
    private val bitrateMode: BitrateMode = options.bitrateMode

    private var started = false
    private var stopped = false
    private var paused = false
    private var reconfigureRequested = false
    private var pendingRotation = false
    private val pendingSyncFrame = AtomicBoolean(false)
    private val lastCaptureUs = AtomicLong(0L)
    private var activeCodec: MediaCodec? = null
    private var workerThread: Thread? = null

    private val rotationStateMachine = RotationStateMachine(
        initialGeometry = initialDisplay,
        clock = clock,
        queryGeometry = { captureBackend.queryDisplayGeometry() }
    )

    override fun start() {
        stateLock.withLock {
            if (started || stopped) return
            started = true
            workerThread = Thread(::runLoop, "velocast-encoder-${nextId.getAndIncrement()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun pause() {
        stateLock.withLock {
            if (stopped || paused) return
            paused = true
            stateChanged.signalAll()
        }
        frameSink.clearMediaQueue()
    }

    override fun resume() {
        pendingSyncFrame.set(true)
        stateLock.withLock {
            if (stopped || !paused) return
            paused = false
            stateChanged.signalAll()
        }
    }

    override fun notifyRotationChanged() {
        rotationStateMachine.notifyRotationChanged()
    }

    override fun requestKeyframe() {
        pendingSyncFrame.set(true)
        val codec = stateLock.withLock { activeCodec } ?: return
        EncoderCodecHelpers.requestSyncFrameQuietly(codec, logger)
    }

    override fun reconfigure(bitrateBps: Int, fps: Int, scale: Float) {
        Validators.bitrateBps(bitrateBps)
        Validators.fps(fps.toFloat())
        Validators.scale(scale)

        val codecToUpdate: MediaCodec? = stateLock.withLock {
            val fpsChanged = fps != this.fps
            val scaleChanged = kotlin.math.abs(scale - this.resolutionScale) > SCALE_EPSILON
            val bitrateChanged = bitrateBps != this.bitrateBps

            this.bitrateBps = bitrateBps
            this.fps = fps
            this.resolutionScale = scale

            if (!fpsChanged && !scaleChanged && bitrateChanged) {
                activeCodec
            } else {
                if (fpsChanged || scaleChanged) {
                    reconfigureRequested = true
                    stateChanged.signalAll()
                }
                null
            }
        }

        if (codecToUpdate != null && tryUpdateBitrate(codecToUpdate, bitrateBps)) {
            frameSink.enqueuePriority(Status(StatusKind.BITRATE, bitrateBps))
            return
        }
        stateLock.withLock {
            if (fps != this.fps || kotlin.math.abs(scale - this.resolutionScale) > SCALE_EPSILON) {
                reconfigureRequested = true
                stateChanged.signalAll()
            }
        }
    }

    override fun close() {
        val threadToJoin: Thread?
        stateLock.withLock {
            if (stopped) return
            stopped = true
            threadToJoin = workerThread
            stateChanged.signalAll()
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn(TAG, "Interrupted waiting for encoder shutdown", e)
            }
        }
    }

    private fun tryUpdateBitrate(codec: MediaCodec, targetBps: Int): Boolean = try {
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBps)
        }
        codec.setParameters(params)
        logger.info(TAG, "Updated bitrate dynamically to $targetBps bps")
        true
    } catch (e: IllegalStateException) {
        logger.warn(TAG, "Dynamic bitrate unsupported; scheduling reconfigure", e)
        stateLock.withLock {
            reconfigureRequested = true
            stateChanged.signalAll()
        }
        false
    }

    private fun runLoop() {
        var isReset = false
        var keepRunning = true
        while (keepRunning &&
            EncoderStateHelpers.waitUntilActiveOrStopped(
                stateLock,
                stateChanged,
                { stopped },
                { paused },
                logger
            )
        ) {
            val config = stateLock.withLock {
                EncoderConfig(displayGeometry, bitrateBps, fps, resolutionScale)
            }
            keepRunning = executePassSafely(config, isReset)
            if (keepRunning) {
                val (wasReconfigure, wasRotation) = stateLock.withLock {
                    val req = reconfigureRequested
                    val rot = pendingRotation
                    reconfigureRequested = false
                    pendingRotation = false
                    Pair(req, rot)
                }
                isReset = true
                if (wasReconfigure && !wasRotation) {
                    EncoderStateHelpers.sleepSettlePause(
                        stateLock,
                        stateChanged,
                        { stopped },
                        { paused },
                        logger
                    )
                }
            }
        }
    }

    private fun executePassSafely(config: EncoderConfig, sendReset: Boolean): Boolean = try {
        val passContext = PassExecutionContext(
            frameSink = frameSink,
            clock = clock,
            logger = logger,
            captureBackend = captureBackend,
            bitrateMode = bitrateMode,
            rotationStateMachine = rotationStateMachine,
            pendingSyncFrame = pendingSyncFrame,
            lastCaptureUs = lastCaptureUs,
            onGeometryChanged = onGeometryChanged,
            shouldContinue = {
                stateLock.withLock {
                    !stopped && !paused && !reconfigureRequested &&
                        !pendingRotation
                }
            },
            onDisplayUpdated = { live ->
                stateLock.withLock {
                    val prev = displayGeometry
                    displayGeometry = live
                    prev
                }
            },
            onRotationDetected = { rotated ->
                stateLock.withLock {
                    displayGeometry = rotated
                    pendingRotation = true
                }
                onGeometryChanged(rotated)
            },
            onActiveCodecChanged = { codec -> stateLock.withLock { activeCodec = codec } }
        )
        EncoderPassRunner(passContext).runSingleCodecPass(config, sendReset)
        true
    } catch (e: UnsupportedPlatformException) {
        logger.error(TAG, "Display bridge unsupported on this device", e)
        false
    } catch (e: MediaCodec.CodecException) {
        logger.error(TAG, "MediaCodec failure; stopping encoder", e)
        false
    } catch (e: IllegalStateException) {
        logger.error(TAG, "Encoder illegal state; stopping encoder", e)
        false
    } catch (e: IOException) {
        logger.info(TAG, "Video stream write ended", e)
        false
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val SCALE_EPSILON = 0.02f
        private val nextId = AtomicInteger(1)
    }
}

private data class EncoderConfig(
    val display: DisplayGeometry,
    val bitrateBps: Int,
    val fps: Int,
    val scale: Float
)

private class PassExecutionContext(
    val frameSink: SessionFrameSink,
    val clock: ServerClock,
    val logger: Logger,
    val captureBackend: CaptureBackend,
    val bitrateMode: BitrateMode,
    val rotationStateMachine: RotationStateMachine,
    val pendingSyncFrame: AtomicBoolean,
    val lastCaptureUs: AtomicLong,
    val onGeometryChanged: (DisplayGeometry) -> Unit,
    val shouldContinue: () -> Boolean,
    val onDisplayUpdated: (DisplayGeometry) -> DisplayGeometry,
    val onRotationDetected: (DisplayGeometry) -> Unit,
    val onActiveCodecChanged: (MediaCodec?) -> Unit
)

private class EncoderPassRunner(private val ctx: PassExecutionContext) {
    fun runSingleCodecPass(config: EncoderConfig, sendReset: Boolean) {
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var captureHandle: Closeable? = null

        try {
            val liveDisplay = ctx.captureBackend.queryDisplayGeometry()
            val previousDisplay = ctx.onDisplayUpdated(liveDisplay)
            ctx.rotationStateMachine.updateBaseline(liveDisplay)
            if (liveDisplay != previousDisplay) {
                ctx.onGeometryChanged(liveDisplay)
            }

            val encodeWidth =
                EncoderCodecHelpers.alignTo16((liveDisplay.width * config.scale).roundToInt())
            val encodeHeight =
                EncoderCodecHelpers.alignTo16((liveDisplay.height * config.scale).roundToInt())

            val configured = EncoderCodecHelpers.createAndConfigure(
                encodeWidth = encodeWidth,
                encodeHeight = encodeHeight,
                bitrate = config.bitrateBps,
                frameRate = config.fps,
                bitrateMode = ctx.bitrateMode,
                logger = ctx.logger
            )
            codec = configured.codec
            inputSurface = codec.createInputSurface()
            captureHandle = ctx.captureBackend.startCapture(inputSurface, encodeWidth, encodeHeight)
            codec.start()
            ctx.onActiveCodecChanged(codec)
            if (ctx.pendingSyncFrame.getAndSet(false)) {
                EncoderCodecHelpers.requestSyncFrameQuietly(codec, ctx.logger)
            }

            ctx.frameSink.enqueuePriority(
                Status(
                    StatusKind.BITRATE,
                    configured.settings.bitrateBps,
                    configured.settings.toStatusDetail()
                )
            )
            ctx.frameSink.enqueuePriority(Status(StatusKind.FPS, configured.settings.fps))
            ctx.logger.info(
                TAG,
                "Started H.264 encoder ${encodeWidth}x$encodeHeight @ ${config.fps}fps, " +
                    "${config.bitrateBps} bps (${configured.settings.toStatusDetail()})"
            )
            drainLoop(codec, liveDisplay, encodeWidth, encodeHeight, sendReset)
        } finally {
            ctx.onActiveCodecChanged(null)
            closeCaptureQuietly(captureHandle)
            EncoderCodecHelpers.releaseResources(codec, inputSurface, ctx.logger)
        }
    }

    private fun drainLoop(
        codec: MediaCodec,
        display: DisplayGeometry,
        encodeWidth: Int,
        encodeHeight: Int,
        sendReset: Boolean
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val passState = DrainPassState(sendReset = sendReset)

        while (ctx.shouldContinue()) {
            val rotated = ctx.rotationStateMachine.pollIfDue()
            if (rotated != null) {
                ctx.logger.info(
                    TAG,
                    "Display geometry changed " +
                        "${display.width}x${display.height}@${display.rotation} " +
                        "-> ${rotated.width}x${rotated.height}@${rotated.rotation}"
                )
                ctx.onRotationDetected(rotated)
                break
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex >= 0) {
                processOutputBuffer(
                    codec,
                    outputIndex,
                    bufferInfo,
                    display,
                    encodeWidth,
                    encodeHeight,
                    passState
                )
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleOutputFormatChanged(codec, display, encodeWidth, encodeHeight, passState)
            }
        }
    }

    private fun handleOutputFormatChanged(
        codec: MediaCodec,
        display: DisplayGeometry,
        encodeWidth: Int,
        encodeHeight: Int,
        passState: DrainPassState
    ) {
        val format = codec.outputFormat
        ctx.logger.debug(TAG, "Encoder output format changed: $format")
        val csd = H264NalHelper.extractCsdFromFormat(format)
        if (csd != null) {
            passState.cachedCsd = csd
            if (!passState.sentHeader) {
                emitStreamHeaders(encodeWidth, encodeHeight, display, csd, passState)
            }
        }
    }

    private fun processOutputBuffer(
        codec: MediaCodec,
        outputIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        display: DisplayGeometry,
        encodeWidth: Int,
        encodeHeight: Int,
        passState: DrainPassState
    ) {
        val outputBuffer = codec.getOutputBuffer(outputIndex)
        if (outputBuffer != null && bufferInfo.size > 0) {
            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val bytes = readBytes(outputBuffer, bufferInfo)
            if (isConfig) {
                passState.cachedCsd = bytes
                emitStreamHeaders(encodeWidth, encodeHeight, display, bytes, passState)
            } else if (ensureHeadersEmitted(
                    codec,
                    bytes,
                    isKey,
                    display,
                    encodeWidth,
                    encodeHeight,
                    passState
                )
            ) {
                if (!passState.sentFirstFrame && !isKey) {
                    EncoderCodecHelpers.requestSyncFrameQuietly(codec, ctx.logger)
                } else {
                    enqueueEncodedFrame(codec, bytes, bufferInfo, isKey, passState)
                }
            }
        }
    }

    private fun ensureHeadersEmitted(
        codec: MediaCodec,
        frameBytes: ByteArray,
        isKey: Boolean,
        display: DisplayGeometry,
        encodeWidth: Int,
        encodeHeight: Int,
        passState: DrainPassState
    ): Boolean {
        if (passState.sentHeader) return true
        val fallbackCsd =
            (if (isKey) H264NalHelper.extractSpsPps(frameBytes, 0, frameBytes.size) else null)
                ?: passState.cachedCsd
                ?: H264NalHelper.extractCsdFromFormat(codec.outputFormat)
        if (fallbackCsd != null) {
            passState.cachedCsd = fallbackCsd
            emitStreamHeaders(encodeWidth, encodeHeight, display, fallbackCsd, passState)
        }
        return passState.sentHeader
    }

    private fun enqueueEncodedFrame(
        codec: MediaCodec,
        frameBytes: ByteArray,
        bufferInfo: MediaCodec.BufferInfo,
        isKey: Boolean,
        passState: DrainPassState
    ) {
        val csdToPrepend = if (isKey) {
            val cached = passState.cachedCsd
            if (cached != null &&
                !H264NalHelper.containsSpsAndPps(frameBytes, 0, frameBytes.size)
            ) {
                cached
            } else {
                null
            }
        } else {
            null
        }

        val totalLen = (csdToPrepend?.size ?: 0) + frameBytes.size
        val slot = ctx.frameSink.acquireVideoSlot(totalLen)
        var writePos = ProtocolV2.FRAME_HEADER_SIZE
        if (csdToPrepend != null) {
            csdToPrepend.copyInto(slot.buffer, writePos)
            writePos += csdToPrepend.size
        }
        frameBytes.copyInto(slot.buffer, writePos)

        val resetBit = if (passState.sendReset && !passState.sentFirstFrame && isKey) {
            FrameFlags.RESET
        } else {
            0
        }
        slot.payloadLength = totalLen
        slot.flags = (if (isKey) FrameFlags.KEYFRAME else 0) or resetBit
        slot.ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
        slot.captureUs = nextMonotonicCaptureUs()

        ctx.frameSink.enqueueVideo(slot)
        passState.sentFirstFrame = true

        if (isKey) {
            ctx.pendingSyncFrame.set(false)
            passState.nonKeyAfterSyncRequest = 0
        } else if (ctx.pendingSyncFrame.get()) {
            passState.nonKeyAfterSyncRequest++
            if (passState.nonKeyAfterSyncRequest >= SYNC_RETRY_FRAMES) {
                passState.nonKeyAfterSyncRequest = 0
                EncoderCodecHelpers.requestSyncFrameQuietly(codec, ctx.logger)
            }
        }
    }

    private fun emitStreamHeaders(
        encodeWidth: Int,
        encodeHeight: Int,
        display: DisplayGeometry,
        csd: ByteArray,
        passState: DrainPassState
    ) {
        ctx.frameSink.beginStreamGeneration(
            videoSize = VideoSize(encodeWidth, encodeHeight, display),
            codecConfig = CodecConfig(Codec.H264, csd),
            isReset = passState.sendReset
        )
        passState.sentHeader = true
    }

    private fun nextMonotonicCaptureUs(): Long {
        val now = ctx.clock.monotonicMicros().coerceAtLeast(0L)
        while (true) {
            val prev = ctx.lastCaptureUs.get()
            val next = maxOf(prev, now)
            if (ctx.lastCaptureUs.compareAndSet(prev, next)) {
                return next
            }
        }
    }

    private fun readBytes(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        val out = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(out)
        return out
    }

    private fun closeCaptureQuietly(handle: Closeable?) {
        if (handle == null) return
        try {
            handle.close()
        } catch (e: IOException) {
            ctx.logger.warn(TAG, "Error closing capture handle", e)
        } catch (e: IllegalStateException) {
            ctx.logger.warn(TAG, "Illegal state closing capture handle", e)
        }
    }

    private class DrainPassState(
        val sendReset: Boolean,
        var cachedCsd: ByteArray? = null,
        var sentHeader: Boolean = false,
        var sentFirstFrame: Boolean = false,
        var nonKeyAfterSyncRequest: Int = 0
    )

    private companion object {
        const val TAG = "ScreenEncoder"
        const val DEQUEUE_TIMEOUT_US = 50_000L
        const val SYNC_RETRY_FRAMES = 2
    }
}

private object EncoderStateHelpers {
    private const val TAG = "ScreenEncoder"
    private const val RECONFIGURE_SETTLE_MS = 200L

    fun waitUntilActiveOrStopped(
        stateLock: ReentrantLock,
        stateChanged: Condition,
        isStopped: () -> Boolean,
        isPaused: () -> Boolean,
        logger: Logger
    ): Boolean {
        stateLock.withLock {
            while (!isStopped() && isPaused()) {
                try {
                    stateChanged.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.debug(TAG, "Encoder wait interrupted", e)
                    return false
                }
            }
            return !isStopped()
        }
    }

    fun sleepSettlePause(
        stateLock: ReentrantLock,
        stateChanged: Condition,
        isStopped: () -> Boolean,
        isPaused: () -> Boolean,
        logger: Logger
    ) {
        stateLock.withLock {
            if (!isStopped() && !isPaused()) {
                try {
                    stateChanged.await(RECONFIGURE_SETTLE_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.debug(TAG, "Reconfigure settle interrupted", e)
                }
            }
        }
    }
}

private data class ConfiguredCodec(val codec: MediaCodec, val settings: SelectedEncoderSettings)

private object EncoderCodecHelpers {
    private const val TAG = "ScreenEncoder"
    private const val ALIGN_16_MASK = 15
    private const val ALIGN_16_MIN = 16

    fun alignTo16(value: Int): Int = (value and ALIGN_16_MASK.inv()).coerceAtLeast(ALIGN_16_MIN)

    fun requestSyncFrameQuietly(codec: MediaCodec, logger: Logger) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Keyframe request failed", e)
        }
    }

    fun createAndConfigure(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int,
        bitrateMode: BitrateMode,
        logger: Logger
    ): ConfiguredCodec {
        val probe = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = queryCapabilities(probe, logger)
        val candidates = EncoderFormatBuilder.buildCandidates(
            encodeWidth = encodeWidth,
            encodeHeight = encodeHeight,
            bitrateBps = bitrate,
            fps = frameRate,
            requestedMode = bitrateMode,
            caps = caps
        )

        var currentCodec: MediaCodec? = probe
        var lastError: RuntimeException? = null
        for (candidate in candidates) {
            val target = currentCodec
                ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            currentCodec = null
            try {
                target.configure(candidate.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return ConfiguredCodec(target, candidate.settings)
            } catch (e: MediaCodec.CodecException) {
                lastError = e
                logger.warn(
                    TAG,
                    "Encoder format rejected (${candidate.settings.toStatusDetail()}); retrying",
                    e
                )
            } catch (e: IllegalArgumentException) {
                lastError = e
                logger.warn(
                    TAG,
                    "Encoder format invalid (${candidate.settings.toStatusDetail()}); retrying",
                    e
                )
            }
            releaseQuietly(target, logger)
        }

        throw lastError ?: IllegalStateException("Unable to configure H.264 encoder")
    }

    private fun queryCapabilities(codec: MediaCodec, logger: Logger): CodecCapabilitiesSnapshot? =
        try {
            CodecCapabilitiesSnapshot.fromMediaCodecInfo(codec.codecInfo)
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Unable to query MediaCodecInfo capabilities", e)
            null
        } catch (e: IllegalArgumentException) {
            logger.warn(TAG, "MediaCodecInfo lacks AVC capabilities", e)
            null
        }

    private fun releaseQuietly(codec: MediaCodec, logger: Logger) {
        try {
            codec.release()
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Error releasing unconfigured encoder", e)
        }
    }

    fun releaseResources(codec: MediaCodec?, inputSurface: Surface?, logger: Logger) {
        if (codec != null) {
            try {
                codec.stop()
            } catch (e: IllegalStateException) {
                logger.warn(TAG, "Error stopping MediaCodec", e)
            }
            try {
                codec.release()
            } catch (e: IllegalStateException) {
                logger.warn(TAG, "Error releasing MediaCodec", e)
            }
        }
        if (inputSurface != null) {
            try {
                inputSurface.release()
            } catch (e: IllegalStateException) {
                logger.warn(TAG, "Error releasing encoder Surface", e)
            }
        }
    }
}
