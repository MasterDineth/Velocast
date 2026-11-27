package com.mastersoft.velocast.client

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.VideoSize
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle state exposed by [VideoDecoder]. */
sealed interface DecoderState {
    data object Idle : DecoderState
    data object WaitingForSurface : DecoderState
    data class Running(val width: Int, val height: Int, val generation: Int) : DecoderState
    data class Recovering(val attempt: Int, val reason: String) : DecoderState
    data class Failed(val error: SessionError) : DecoderState
}

/** Normalized codec error metadata mirroring [MediaCodec.CodecException]. */
data class CodecFailure(
    val isRecoverable: Boolean,
    val isTransient: Boolean,
    val diagnosticInfo: String,
    val cause: Throwable? = null
) {
    companion object {
        fun fromCodecException(exception: MediaCodec.CodecException): CodecFailure = CodecFailure(
            isRecoverable = exception.isRecoverable,
            isTransient = exception.isTransient,
            diagnosticInfo = exception.diagnosticInfo.ifBlank {
                exception.message
                    ?: "CodecException"
            },
            cause = exception
        )
    }
}

class DecoderCodecException(val failure: CodecFailure) :
    IllegalStateException(failure.diagnosticInfo, failure.cause)

data class DecoderFormatConfig(
    val width: Int,
    val height: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val lowLatency: Boolean = true
)

interface DecoderCodecCallback {
    fun onInputBufferAvailable(index: Int)
    fun onOutputBufferAvailable(index: Int, presentationTimeUs: Long, flags: Int, size: Int)
    fun onOutputFormatChanged(width: Int, height: Int)
    fun onError(failure: CodecFailure)
}

interface DecoderCodecAdapter {
    fun setCallback(callback: DecoderCodecCallback)
    fun configure(config: DecoderFormatConfig, surface: Surface)
    fun start()
    fun flush()
    fun reset()
    fun setOutputSurface(surface: Surface)
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun stop()
    fun release()
}

fun interface DecoderCodecFactory {
    fun createAvcDecoder(): DecoderCodecAdapter
}

/** Production [DecoderCodecFactory] backed by asynchronous [MediaCodec] callbacks. */
object MediaCodecDecoderFactory : DecoderCodecFactory {
    override fun createAvcDecoder(): DecoderCodecAdapter = AndroidMediaCodecAdapter()
}

private object AndroidCodecFormatHelper {
    private const val MAX_INPUT_SIZE_BYTES = 2 * 1024 * 1024

    fun configureWithFallback(codec: MediaCodec, config: DecoderFormatConfig, surface: Surface) {
        val bounds = resolveMaxSupportedDimensions(codec, config)
        val primary = buildMediaFormat(
            width = config.width,
            height = config.height,
            maxWidth = bounds.first,
            maxHeight = bounds.second,
            lowLatency = config.lowLatency
        )
        try {
            codec.configure(primary, surface, null, 0)
        } catch (e: MediaCodec.CodecException) {
            if (!config.lowLatency) throw DecoderCodecException(CodecFailure.fromCodecException(e))
            configureNoLowLatency(codec, config, bounds, surface)
        } catch (e: IllegalArgumentException) {
            if (!config.lowLatency) throw e
            configureNoLowLatency(codec, config, bounds, surface)
        }
    }

    private fun configureNoLowLatency(
        codec: MediaCodec,
        config: DecoderFormatConfig,
        bounds: Pair<Int, Int>,
        surface: Surface
    ) {
        val fallback = buildMediaFormat(
            width = config.width,
            height = config.height,
            maxWidth = bounds.first,
            maxHeight = bounds.second,
            lowLatency = false
        )
        try {
            codec.configure(fallback, surface, null, 0)
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    fun resolveMaxSupportedDimensions(
        codec: MediaCodec,
        config: DecoderFormatConfig
    ): Pair<Int, Int> {
        var maxW = maxOf(config.width, config.maxWidth)
        var maxH = maxOf(config.height, config.maxHeight)
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val videoCaps: MediaCodecInfo.VideoCapabilities? = caps.videoCapabilities
            if (videoCaps != null) {
                maxW = maxW.coerceIn(config.width, videoCaps.supportedWidths.upper)
                maxH = maxH.coerceIn(config.height, videoCaps.supportedHeights.upper)
            }
        } catch (_: IllegalStateException) {
            // Fall back to requested adaptive ceiling.
        } catch (_: IllegalArgumentException) {
            // Fall back to requested adaptive ceiling.
        }
        return maxW to maxH
    }

    fun buildMediaFormat(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        lowLatency: Boolean
    ): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        .apply {
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxHeight)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE_BYTES)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

    fun resolveCroppedWidth(fmt: MediaFormat): Int =
        if (fmt.containsKey("crop-left") && fmt.containsKey("crop-right")) {
            fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_WIDTH)) {
            fmt.getInteger(MediaFormat.KEY_WIDTH)
        } else {
            0
        }

    fun resolveCroppedHeight(fmt: MediaFormat): Int =
        if (fmt.containsKey("crop-top") && fmt.containsKey("crop-bottom")) {
            fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
            fmt.getInteger(MediaFormat.KEY_HEIGHT)
        } else {
            0
        }
}

private class AndroidMediaCodecAdapter : DecoderCodecAdapter {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var callbackThread: HandlerThread? = null

    override fun setCallback(callback: DecoderCodecCallback) {
        val thread = HandlerThread("velocast-decoder-cb").apply { start() }
        callbackThread = thread
        val handler = Handler(thread.looper)
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    callback.onInputBufferAvailable(index)
                }

                override fun onOutputBufferAvailable(
                    mc: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    callback.onOutputBufferAvailable(
                        index = index,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags,
                        size = info.size
                    )
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    val w = AndroidCodecFormatHelper.resolveCroppedWidth(format)
                    val h = AndroidCodecFormatHelper.resolveCroppedHeight(format)
                    if (w > 0 && h > 0) {
                        callback.onOutputFormatChanged(w, h)
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    callback.onError(CodecFailure.fromCodecException(e))
                }
            },
            handler
        )
    }

    override fun configure(config: DecoderFormatConfig, surface: Surface) {
        AndroidCodecFormatHelper.configureWithFallback(codec, config, surface)
    }

    override fun start() {
        try {
            codec.start()
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun flush() {
        try {
            codec.flush()
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun reset() {
        try {
            codec.reset()
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun setOutputSurface(surface: Surface) {
        try {
            codec.setOutputSurface(surface)
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun getInputBuffer(index: Int): ByteBuffer? = try {
        codec.getInputBuffer(index)
    } catch (e: MediaCodec.CodecException) {
        throw DecoderCodecException(CodecFailure.fromCodecException(e))
    }

    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ) {
        try {
            codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        try {
            codec.releaseOutputBuffer(index, render)
        } catch (e: MediaCodec.CodecException) {
            throw DecoderCodecException(CodecFailure.fromCodecException(e))
        }
    }

    override fun stop() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // Already stopped/uninitialized.
        }
    }

    override fun release() {
        try {
            codec.release()
        } catch (_: IllegalStateException) {
            // Best-effort release.
        } finally {
            callbackThread?.quitSafely()
            callbackThread = null
        }
    }
}

private class InFlightTiming {
    var ptsUs: Long = 0L
    var seq: Int = 0
    var captureUs: Long = 0L
    var receivedAtUs: Long = 0L
    var dequeuedForDecodeUs: Long = 0L
}

private class PendingDecoderInput(
    val isConfig: Boolean,
    val ptsUs: Long,
    val flags: Int,
    val frame: ReceivedFrame?,
    val configBytes: ByteArray?,
    val timingSlotIndex: Int
) {
    fun release() {
        frame?.release()
    }
}

private class PendingDecoderOutput(
    val generation: Int,
    val index: Int,
    val ptsUs: Long,
    val flags: Int,
    val size: Int,
    val decodedAtUs: Long
)

private class DecoderRenderWorker(
    private val clock: SessionClock,
    private val renderOutput: (PendingDecoderOutput) -> Unit
) {
    private val renderLock = ReentrantLock()
    private val renderReady = renderLock.newCondition()
    private val pendingOutputs = ArrayDeque<PendingDecoderOutput>()
    private var renderThread: Thread? = null
    private var stopped = false

    fun start() {
        renderLock.withLock {
            if (renderThread != null) return
            stopped = false
            renderThread = Thread({ runLoop() }, "velocast-decoder-render").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun enqueue(output: PendingDecoderOutput) {
        renderLock.withLock {
            if (!stopped) {
                pendingOutputs.addLast(output)
                renderReady.signalAll()
            }
        }
    }

    fun clear() {
        renderLock.withLock { pendingOutputs.clear() }
    }

    fun stop(joinTimeoutMs: Long) {
        val threadToJoin: Thread?
        renderLock.withLock {
            stopped = true
            pendingOutputs.clear()
            renderReady.signalAll()
            threadToJoin = renderThread
            renderThread = null
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join(joinTimeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun nowMicros(): Long = clock.monotonicMicros()

    private fun runLoop() {
        while (true) {
            val item = renderLock.withLock {
                while (pendingOutputs.isEmpty() && !stopped) {
                    try {
                        renderReady.await()
                    } catch (_: InterruptedException) {
                        if (stopped) return@withLock null
                    }
                }
                if (stopped) null else pendingOutputs.removeFirst()
            } ?: break

            renderOutput(item)
        }
    }
}

private class DecoderInputQueue(
    private val inputQueueCapacity: Int,
    private val clock: SessionClock
) {
    private val pendingInputs = ArrayDeque<PendingDecoderInput>(inputQueueCapacity)
    private val availableInputIndices = ArrayDeque<Int>()
    private val timingRing = Array(TIMING_RING_SIZE) { InFlightTiming() }
    private var timingCursor = 0

    fun addAvailableBufferIndex(index: Int) {
        availableInputIndices.addLast(index)
    }

    fun recordTimingStart(frame: ReceivedFrame): Int {
        val idx = timingCursor
        timingCursor = (timingCursor + 1) % TIMING_RING_SIZE
        val receivedUs = if (frame.receivedAtUs > 0L) {
            frame.receivedAtUs
        } else {
            clock.monotonicMicros().coerceAtLeast(1L)
        }
        val slot = timingRing[idx]
        slot.ptsUs = frame.ptsUs
        slot.seq = frame.seq
        slot.captureUs = frame.captureUs
        slot.receivedAtUs = receivedUs
        slot.dequeuedForDecodeUs = receivedUs
        return idx
    }

    fun takeTimingForPts(ptsUs: Long): InFlightTiming? {
        for (slot in timingRing) {
            if (slot.ptsUs == ptsUs && slot.receivedAtUs > 0L) {
                return slot
            }
        }
        return null
    }

    fun enqueue(
        input: PendingDecoderInput,
        codec: DecoderCodecAdapter?,
        generation: Int,
        onCodecFailure: (CodecFailure, Int) -> Unit
    ): Boolean {
        var droppedVideo = false
        if (pendingInputs.size >= inputQueueCapacity) {
            val evicted = evictOldestVideoInput()
            if (evicted != null) {
                evicted.release()
                droppedVideo = true
            } else if (!input.isConfig) {
                input.release()
                return true
            }
        }
        pendingInputs.addLast(input)
        drainPairs(codec, generation, onCodecFailure)
        return droppedVideo
    }

    fun drainPairs(
        codec: DecoderCodecAdapter?,
        generation: Int,
        onCodecFailure: (CodecFailure, Int) -> Unit
    ) {
        val activeCodec = codec ?: return
        while (pendingInputs.isNotEmpty() && availableInputIndices.isNotEmpty()) {
            val bufferIndex = availableInputIndices.removeFirst()
            val input = pendingInputs.removeFirst()
            submitInputToCodec(activeCodec, generation, bufferIndex, input, onCodecFailure)
        }
    }

    fun clear() {
        while (pendingInputs.isNotEmpty()) {
            pendingInputs.removeFirst().release()
        }
        availableInputIndices.clear()
    }

    private fun evictOldestVideoInput(): PendingDecoderInput? {
        val iter = pendingInputs.iterator()
        while (iter.hasNext()) {
            val candidate = iter.next()
            if (!candidate.isConfig) {
                iter.remove()
                return candidate
            }
        }
        return null
    }

    private fun submitInputToCodec(
        activeCodec: DecoderCodecAdapter,
        gen: Int,
        bufferIndex: Int,
        input: PendingDecoderInput,
        onCodecFailure: (CodecFailure, Int) -> Unit
    ) {
        try {
            val byteBuf = activeCodec.getInputBuffer(bufferIndex)
            val srcBytes = input.configBytes ?: input.frame?.payload
            val srcLen = input.configBytes?.size ?: input.frame?.payloadLength ?: 0
            if (byteBuf != null && srcBytes != null && srcLen > 0) {
                byteBuf.clear()
                val copyLen = srcLen.coerceAtMost(byteBuf.remaining())
                byteBuf.put(srcBytes, 0, copyLen)
                if (input.timingSlotIndex >= 0) {
                    timingRing[input.timingSlotIndex].dequeuedForDecodeUs = clock.monotonicMicros()
                }
                activeCodec.queueInputBuffer(bufferIndex, 0, copyLen, input.ptsUs, input.flags)
            }
        } catch (e: DecoderCodecException) {
            onCodecFailure(e.failure, gen)
        } catch (e: IllegalStateException) {
            onCodecFailure(CodecFailure(true, false, e.message ?: "queue", e), gen)
        } finally {
            input.release()
        }
    }

    private companion object {
        const val TIMING_RING_SIZE = 32
    }
}

private class DecoderStateStore(
    initialWidth: Int,
    initialHeight: Int,
    val maxAdaptiveWidth: Int,
    val maxAdaptiveHeight: Int,
    val maxConsecutiveRecoveryAttempts: Int
) {
    val lock = ReentrantLock()
    val activeGeneration = AtomicInteger(0)
    val activeCodecId = AtomicInteger(0)
    val mutableState = MutableStateFlow<DecoderState>(DecoderState.Idle)
    val recreateCounter = AtomicInteger(0)
    val resetCounter = AtomicInteger(0)

    var codec: DecoderCodecAdapter? = null
    var activeSurface: Surface? = null
    var surfaceValid = false
    var started = false
    var stopped = false
    var configuredWidth = initialWidth
    var configuredHeight = initialHeight
    var pendingVideoSizeWidth: Int? = null
    var pendingVideoSizeHeight: Int? = null
    var latestConfig: CodecConfig? = null
    var consecutiveFailures = 0

    fun isReadyForInput(payloadLength: Int): Boolean {
        val active = started && !stopped
        return active && codec != null && payloadLength > 0
    }

    fun isValidFormatChange(generation: Int, width: Int, height: Int): Boolean {
        val currentGen = !stopped && generation == activeGeneration.get()
        return currentGen && width > 0 && height > 0
    }

    fun resolveConfigDimensions(parsed: StreamDimensions?): Pair<Int, Int> {
        val newW = parsed?.width ?: pendingVideoSizeWidth ?: configuredWidth
        val newH = parsed?.height ?: pendingVideoSizeHeight ?: configuredHeight
        pendingVideoSizeWidth = null
        pendingVideoSizeHeight = null
        return newW to newH
    }

    fun releaseActiveCodec() {
        activeCodecId.incrementAndGet()
        val target = codec
        codec = null
        if (target != null) {
            target.stop()
            target.release()
        }
    }
}

private class DecoderCodecLifecycle(
    private val store: DecoderStateStore,
    private val inputQueue: DecoderInputQueue,
    private val renderWorker: DecoderRenderWorker,
    private val codecFactory: DecoderCodecFactory,
    private val callbackFactory: (Int) -> DecoderCodecCallback,
    private val onFailure: (CodecFailure, Int) -> Unit
) {
    fun clearQueuesLocked() {
        inputQueue.clear()
        renderWorker.clear()
    }

    fun configureAndStartLocked(
        width: Int,
        height: Int,
        targetSurface: Surface,
        recreate: Boolean
    ) {
        val gen = store.activeGeneration.incrementAndGet()
        clearQueuesLocked()
        if (recreate) store.releaseActiveCodec()

        val adapter = store.codec ?: codecFactory.createAvcDecoder().also { created ->
            val codecId = store.activeCodecId.incrementAndGet()
            created.setCallback(callbackFactory(codecId))
            store.codec = created
        }

        val formatConfig = DecoderFormatConfig(
            width = width,
            height = height,
            maxWidth = maxOf(width, store.maxAdaptiveWidth),
            maxHeight = maxOf(height, store.maxAdaptiveHeight),
            lowLatency = true
        )
        try {
            adapter.configure(formatConfig, targetSurface)
            adapter.start()
            store.configuredWidth = width
            store.configuredHeight = height
            store.mutableState.value = DecoderState.Running(width, height, gen)
        } catch (e: DecoderCodecException) {
            onFailure(e.failure, gen)
        } catch (e: IllegalStateException) {
            onFailure(CodecFailure(false, false, e.message ?: "start", e), gen)
        }
    }

    fun attachSurfaceToExistingLocked(existing: DecoderCodecAdapter, surface: Surface) {
        try {
            existing.setOutputSurface(surface)
            store.mutableState.value = DecoderState.Running(
                store.configuredWidth,
                store.configuredHeight,
                store.activeGeneration.get()
            )
        } catch (e: DecoderCodecException) {
            onFailure(e.failure, store.activeGeneration.get())
        } catch (e: IllegalStateException) {
            onFailure(
                CodecFailure(true, false, e.message ?: "surface", e),
                store.activeGeneration.get()
            )
        }
    }

    fun enqueueCachedConfigLocked() {
        val cfg = store.latestConfig ?: return
        enqueueConfigBytesLocked(cfg.csd)
    }

    fun enqueueConfigBytesLocked(csd: ByteArray) {
        inputQueue.enqueue(
            PendingDecoderInput(
                isConfig = true,
                ptsUs = 0L,
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                frame = null,
                configBytes = csd,
                timingSlotIndex = -1
            ),
            store.codec,
            store.activeGeneration.get(),
            onFailure
        )
    }

    fun flushForResetLocked(): Boolean = try {
        store.activeGeneration.incrementAndGet()
        clearQueuesLocked()
        store.codec?.flush()
        store.codec?.start()
        enqueueCachedConfigLocked()
        true
    } catch (e: DecoderCodecException) {
        onFailure(e.failure, store.activeGeneration.get())
        false
    }
}

private class DecoderRecoveryController(
    private val store: DecoderStateStore,
    private val lifecycle: DecoderCodecLifecycle,
    private val metrics: SessionMetrics?,
    private val recoveryExecutor: Executor,
    private val onNeedKeyframe: () -> Unit,
    private val onFatalError: (SessionError) -> Unit
) {
    private val recoveryInFlight = AtomicBoolean(false)

    fun schedule(failure: CodecFailure, failedGeneration: Int) {
        if (store.stopped || !store.started) return
        if (!recoveryInFlight.compareAndSet(false, true)) return
        recoveryExecutor.execute {
            try {
                performRecovery(failure, failedGeneration)
            } finally {
                recoveryInFlight.set(false)
            }
        }
    }

    fun resetOrRecreateLocked(reason: String) {
        val activeCodec = store.codec ?: return
        val targetSurface = store.activeSurface ?: return
        val gen = store.activeGeneration.incrementAndGet()
        lifecycle.clearQueuesLocked()
        try {
            store.resetCounter.incrementAndGet()
            activeCodec.reset()
            activeCodec.configure(
                DecoderFormatConfig(
                    width = store.configuredWidth,
                    height = store.configuredHeight,
                    maxWidth = maxOf(store.configuredWidth, store.maxAdaptiveWidth),
                    maxHeight = maxOf(store.configuredHeight, store.maxAdaptiveHeight),
                    lowLatency = true
                ),
                targetSurface
            )
            activeCodec.start()
            store.mutableState.value =
                DecoderState.Running(store.configuredWidth, store.configuredHeight, gen)
        } catch (e: DecoderCodecException) {
            recreateAfterResetFailureLocked("Reset failed ($reason): ${e.message}", targetSurface)
        } catch (e: IllegalStateException) {
            recreateAfterResetFailureLocked("Reset state ($reason): ${e.message}", targetSurface)
        }
    }

    private fun performRecovery(failure: CodecFailure, failedGeneration: Int) {
        var requestKeyframe = false
        var fatalError: SessionError? = null

        store.lock.withLock {
            if (store.stopped || !store.started ||
                failedGeneration != store.activeGeneration.get()
            ) {
                return
            }
            val targetSurface = store.activeSurface
            store.consecutiveFailures++
            metrics?.recordCodecRecovery(failure.diagnosticInfo)

            if (store.consecutiveFailures > store.maxConsecutiveRecoveryAttempts) {
                fatalError = enterFailedStateLocked(failure)
            } else if (targetSurface == null) {
                store.activeGeneration.incrementAndGet()
                lifecycle.clearQueuesLocked()
                store.releaseActiveCodec()
                store.mutableState.value = DecoderState.WaitingForSurface
            } else {
                store.mutableState.value =
                    DecoderState.Recovering(store.consecutiveFailures, failure.diagnosticInfo)
                recoverActiveCodecLocked(failure, targetSurface)
                if (store.mutableState.value is DecoderState.Running) {
                    lifecycle.enqueueCachedConfigLocked()
                    requestKeyframe = true
                }
            }
        }

        if (fatalError != null) {
            onFatalError(fatalError)
        } else if (requestKeyframe) {
            onNeedKeyframe()
        }
    }

    private fun enterFailedStateLocked(failure: CodecFailure): SessionError {
        store.activeGeneration.incrementAndGet()
        lifecycle.clearQueuesLocked()
        store.releaseActiveCodec()
        val error = SessionError.ServerStartFailed(
            "Decoder failed after ${store.consecutiveFailures} attempts: ${failure.diagnosticInfo}"
        )
        store.mutableState.value = DecoderState.Failed(error)
        return error
    }

    private fun recoverActiveCodecLocked(failure: CodecFailure, targetSurface: Surface) {
        val activeCodec = store.codec
        when {
            failure.isTransient && activeCodec != null -> {
                val gen = store.activeGeneration.incrementAndGet()
                lifecycle.clearQueuesLocked()
                try {
                    store.resetCounter.incrementAndGet()
                    activeCodec.flush()
                    activeCodec.start()
                    store.mutableState.value =
                        DecoderState.Running(store.configuredWidth, store.configuredHeight, gen)
                } catch (e: DecoderCodecException) {
                    resetOrRecreateLocked("Transient flush failed: ${e.message}")
                } catch (e: IllegalStateException) {
                    resetOrRecreateLocked("Transient flush state: ${e.message}")
                }
            }

            failure.isRecoverable && activeCodec != null ->
                resetOrRecreateLocked(failure.diagnosticInfo)

            else -> {
                store.recreateCounter.incrementAndGet()
                lifecycle.configureAndStartLocked(
                    store.configuredWidth,
                    store.configuredHeight,
                    targetSurface,
                    recreate = true
                )
            }
        }
    }

    private fun recreateAfterResetFailureLocked(detail: String, targetSurface: Surface) {
        metrics?.recordDiagnosticsEvent("DECODER_EVENT", detail)
        store.recreateCounter.incrementAndGet()
        lifecycle.configureAndStartLocked(
            store.configuredWidth,
            store.configuredHeight,
            targetSurface,
            recreate = true
        )
    }
}

private class DecoderCore(
    codecFactory: DecoderCodecFactory,
    initialWidth: Int,
    initialHeight: Int,
    maxAdaptiveWidth: Int,
    maxAdaptiveHeight: Int,
    inputQueueCapacity: Int,
    maxConsecutiveRecoveryAttempts: Int,
    clock: SessionClock,
    private val metrics: SessionMetrics?,
    recoveryExecutor: Executor,
    private val onNeedKeyframe: () -> Unit,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onFrameRendered: () -> Unit,
    onFatalError: (SessionError) -> Unit
) {
    private val store = DecoderStateStore(
        initialWidth = initialWidth,
        initialHeight = initialHeight,
        maxAdaptiveWidth = maxAdaptiveWidth,
        maxAdaptiveHeight = maxAdaptiveHeight,
        maxConsecutiveRecoveryAttempts = maxConsecutiveRecoveryAttempts
    )
    private val inputQueue = DecoderInputQueue(inputQueueCapacity, clock)
    private val renderWorker = DecoderRenderWorker(clock) { output -> renderSingleOutput(output) }
    private var recovery: DecoderRecoveryController? = null
    private val lifecycle = DecoderCodecLifecycle(
        store = store,
        inputQueue = inputQueue,
        renderWorker = renderWorker,
        codecFactory = codecFactory,
        callbackFactory = ::CodecCallbackBridge,
        onFailure = { failure, gen -> recovery?.schedule(failure, gen) }
    )

    init {
        recovery = DecoderRecoveryController(
            store = store,
            lifecycle = lifecycle,
            metrics = metrics,
            recoveryExecutor = recoveryExecutor,
            onNeedKeyframe = onNeedKeyframe,
            onFatalError = onFatalError
        )
    }

    val state: StateFlow<DecoderState> = store.mutableState.asStateFlow()
    val recreateCount: Int get() = store.recreateCounter.get()
    val resetCount: Int get() = store.resetCounter.get()

    fun start(surface: Surface? = store.activeSurface) {
        store.lock.withLock {
            if (!store.stopped) {
                if (surface != null) {
                    store.activeSurface = surface
                    store.surfaceValid = true
                }
                if (!store.started) {
                    store.started = true
                    renderWorker.start()
                    val targetSurface = store.activeSurface
                    if (targetSurface == null) {
                        store.mutableState.value = DecoderState.WaitingForSurface
                    } else {
                        lifecycle.configureAndStartLocked(
                            width = store.configuredWidth,
                            height = store.configuredHeight,
                            targetSurface = targetSurface,
                            recreate = true
                        )
                    }
                }
            }
        }
    }

    fun attachSurface(surface: Surface) {
        store.lock.withLock {
            if (store.stopped) return
            store.activeSurface = surface
            store.surfaceValid = true
            if (!store.started) return
            val existing = store.codec
            if (existing == null) {
                lifecycle.configureAndStartLocked(
                    store.configuredWidth,
                    store.configuredHeight,
                    surface,
                    recreate = true
                )
                lifecycle.enqueueCachedConfigLocked()
                onNeedKeyframe()
            } else {
                lifecycle.attachSurfaceToExistingLocked(existing, surface)
            }
        }
    }

    fun releaseSurface() {
        store.lock.withLock {
            store.surfaceValid = false
            store.activeSurface = null
            if (!store.started || store.stopped) return
            store.activeGeneration.incrementAndGet()
            lifecycle.clearQueuesLocked()
            store.releaseActiveCodec()
            store.mutableState.value = DecoderState.WaitingForSurface
        }
    }

    fun onVideoSize(videoSize: VideoSize) {
        store.lock.withLock {
            store.pendingVideoSizeWidth = videoSize.streamWidth
            store.pendingVideoSizeHeight = videoSize.streamHeight
        }
    }

    fun onCodecConfig(config: CodecConfig, flags: Int) {
        if (config.codec != Codec.H264) return
        val parsed = H264SpsParser.parseDimensions(config.csd)
        var sizeChanged = false
        var targetW = 0
        var targetH = 0

        store.lock.withLock {
            store.latestConfig = config
            val dims = store.resolveConfigDimensions(parsed)
            targetW = dims.first
            targetH = dims.second
            sizeChanged = applyConfigUpdateLocked(config, flags, targetW, targetH)
        }
        if (sizeChanged) {
            onVideoSizeChanged(targetW, targetH)
        }
    }

    fun feedFrame(frame: ReceivedFrame) {
        val shouldNotifyDrop = store.lock.withLock {
            if (!store.isReadyForInput(frame.payloadLength)) {
                frame.release()
                return
            }
            if (frame.isReset && frame.isKeyframe && !lifecycle.flushForResetLocked()) {
                frame.release()
                return
            }
            val slotIdx = inputQueue.recordTimingStart(frame)
            val codecFlags = if (frame.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            inputQueue.enqueue(
                PendingDecoderInput(
                    isConfig = false,
                    ptsUs = frame.ptsUs,
                    flags = codecFlags,
                    frame = frame,
                    configBytes = null,
                    timingSlotIndex = slotIdx
                ),
                store.codec,
                store.activeGeneration.get()
            ) { failure, gen -> recovery?.schedule(failure, gen) }
        }
        if (shouldNotifyDrop) {
            metrics?.recordQueueDrop()
            onNeedKeyframe()
        }
    }

    fun stop() {
        store.lock.withLock {
            if (store.stopped) return
            store.stopped = true
            store.started = false
            store.surfaceValid = false
            store.activeGeneration.incrementAndGet()
            lifecycle.clearQueuesLocked()
            store.releaseActiveCodec()
            store.mutableState.value = DecoderState.Idle
        }
        renderWorker.stop(RENDER_STOP_JOIN_MS)
    }

    private fun applyConfigUpdateLocked(
        config: CodecConfig,
        flags: Int,
        newW: Int,
        newH: Int
    ): Boolean {
        var recreated = false
        val surface = store.activeSurface
        if (store.started && !store.stopped && surface != null) {
            val sizeDiffers =
                store.codec == null || newW != store.configuredWidth ||
                    newH != store.configuredHeight
            if (sizeDiffers) {
                store.recreateCounter.incrementAndGet()
                lifecycle.configureAndStartLocked(newW, newH, surface, recreate = true)
                recreated = true
            } else if ((flags and FrameFlags.RESET) != 0) {
                recovery?.resetOrRecreateLocked("CONFIG RESET flag")
            }
            if (store.codec != null) {
                lifecycle.enqueueConfigBytesLocked(config.csd)
            }
        } else {
            store.configuredWidth = newW
            store.configuredHeight = newH
        }
        return recreated
    }

    private fun renderSingleOutput(output: PendingDecoderOutput) {
        val isConfig = (output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        var activeCodec: DecoderCodecAdapter? = null
        var shouldRender = false
        var active = false

        store.lock.withLock {
            if (!store.stopped && store.started &&
                output.generation == store.activeGeneration.get()
            ) {
                activeCodec = store.codec
                shouldRender = store.surfaceValid && !isConfig && output.size > 0
                active = activeCodec != null
            }
        }
        if (!active) return

        val rendered =
            releaseOutputSafely(activeCodec!!, output.index, shouldRender, output.generation)
        if (rendered && shouldRender) {
            recordRenderedFrame(output, renderWorker.nowMicros())
            onFrameRendered()
        }
    }

    private fun releaseOutputSafely(
        targetCodec: DecoderCodecAdapter,
        index: Int,
        render: Boolean,
        generation: Int
    ): Boolean = try {
        targetCodec.releaseOutputBuffer(index, render)
        true
    } catch (e: DecoderCodecException) {
        recovery?.schedule(e.failure, generation)
        false
    } catch (e: IllegalStateException) {
        if (generation == store.activeGeneration.get()) {
            recovery?.schedule(CodecFailure(true, false, e.message ?: "render", e), generation)
        }
        false
    }

    private fun recordRenderedFrame(output: PendingDecoderOutput, renderedAtUs: Long) {
        store.lock.withLock {
            store.consecutiveFailures = 0
            val timing = inputQueue.takeTimingForPts(output.ptsUs)
            if (timing != null) {
                metrics?.recordFrameRendered(
                    seq = timing.seq,
                    ptsUs = timing.ptsUs,
                    captureUs = timing.captureUs,
                    receivedAtUs = timing.receivedAtUs,
                    dequeuedForDecodeUs = timing.dequeuedForDecodeUs,
                    decodedAtUs = output.decodedAtUs,
                    renderedAtUs = renderedAtUs
                )
                timing.receivedAtUs = 0L
            }
        }
    }

    private inner class CodecCallbackBridge(private val callbackCodecId: Int) :
        DecoderCodecCallback {
        override fun onInputBufferAvailable(index: Int) {
            store.lock.withLock {
                if (store.stopped || !store.started ||
                    callbackCodecId != store.activeCodecId.get()
                ) {
                    return
                }
                val generation = store.activeGeneration.get()
                inputQueue.addAvailableBufferIndex(index)
                inputQueue.drainPairs(store.codec, generation) { failure, gen ->
                    recovery?.schedule(failure, gen)
                }
            }
        }

        override fun onOutputBufferAvailable(
            index: Int,
            presentationTimeUs: Long,
            flags: Int,
            size: Int
        ) {
            if (store.stopped || !store.started ||
                callbackCodecId != store.activeCodecId.get()
            ) {
                return
            }
            val generation = store.activeGeneration.get()
            renderWorker.enqueue(
                PendingDecoderOutput(
                    generation = generation,
                    index = index,
                    ptsUs = presentationTimeUs,
                    flags = flags,
                    size = size,
                    decodedAtUs = renderWorker.nowMicros()
                )
            )
        }

        override fun onOutputFormatChanged(width: Int, height: Int) {
            var notify = false
            store.lock.withLock {
                if (callbackCodecId != store.activeCodecId.get()) {
                    return
                }
                val generation = store.activeGeneration.get()
                if (store.isValidFormatChange(generation, width, height)) {
                    notify = (width != store.configuredWidth || height != store.configuredHeight)
                    store.configuredWidth = width
                    store.configuredHeight = height
                    store.mutableState.value =
                        DecoderState.Running(width, height, generation)
                }
            }
            if (notify) {
                onVideoSizeChanged(width, height)
            }
        }

        override fun onError(failure: CodecFailure) {
            if (callbackCodecId != store.activeCodecId.get()) {
                return
            }
            recovery?.schedule(failure, store.activeGeneration.get())
        }
    }

    private companion object {
        const val RENDER_STOP_JOIN_MS = 1_000L
    }
}

/**
 * Asynchronous `MediaCodec` H.264 decoder with adaptive playback, `CONFIG` size-change recreation,
 * bounded input queue, dedicated render thread, and `CodecException` recovery (FUN-02, FUN-04,
 * PERF-07).
 */
class VideoDecoder(
    codecFactory: DecoderCodecFactory = MediaCodecDecoderFactory,
    initialWidth: Int = DEFAULT_WIDTH,
    initialHeight: Int = DEFAULT_HEIGHT,
    maxAdaptiveWidth: Int = DEFAULT_MAX_ADAPTIVE_WIDTH,
    maxAdaptiveHeight: Int = DEFAULT_MAX_ADAPTIVE_HEIGHT,
    inputQueueCapacity: Int = DEFAULT_INPUT_QUEUE_CAPACITY,
    maxConsecutiveRecoveryAttempts: Int = DEFAULT_MAX_RECOVERY_ATTEMPTS,
    clock: SessionClock = SystemSessionClock,
    metrics: SessionMetrics? = null,
    recoveryExecutor: Executor = defaultRecoveryExecutor(),
    onNeedKeyframe: () -> Unit = {},
    onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    onFrameRendered: () -> Unit = {},
    onFatalError: (SessionError) -> Unit = {}
) {
    init {
        require(initialWidth > 0 && initialHeight > 0) { "Initial dimensions must be positive" }
        require(inputQueueCapacity > 0) { "inputQueueCapacity must be positive" }
        require(maxConsecutiveRecoveryAttempts > 0) { "maxConsecutiveRecoveryAttempts must be > 0" }
    }

    private val core = DecoderCore(
        codecFactory = codecFactory,
        initialWidth = initialWidth,
        initialHeight = initialHeight,
        maxAdaptiveWidth = maxAdaptiveWidth,
        maxAdaptiveHeight = maxAdaptiveHeight,
        inputQueueCapacity = inputQueueCapacity,
        maxConsecutiveRecoveryAttempts = maxConsecutiveRecoveryAttempts,
        clock = clock,
        metrics = metrics,
        recoveryExecutor = recoveryExecutor,
        onNeedKeyframe = onNeedKeyframe,
        onVideoSizeChanged = onVideoSizeChanged,
        onFrameRendered = onFrameRendered,
        onFatalError = onFatalError
    )

    val state: StateFlow<DecoderState> = core.state

    val recreateCount: Int
        get() = core.recreateCount

    val resetCount: Int
        get() = core.resetCount

    fun start(surface: Surface? = null) = core.start(surface)

    fun attachSurface(surface: Surface) = core.attachSurface(surface)

    fun releaseSurface() = core.releaseSurface()

    fun onVideoSize(videoSize: VideoSize) = core.onVideoSize(videoSize)

    fun onCodecConfig(config: CodecConfig, flags: Int = FrameFlags.CONFIG) =
        core.onCodecConfig(config, flags)

    fun feedFrame(frame: ReceivedFrame) = core.feedFrame(frame)

    fun stop() = core.stop()

    companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 2400
        const val DEFAULT_MAX_ADAPTIVE_WIDTH = 2560
        const val DEFAULT_MAX_ADAPTIVE_HEIGHT = 2560
        const val DEFAULT_INPUT_QUEUE_CAPACITY = 4
        const val DEFAULT_MAX_RECOVERY_ATTEMPTS = 3

        private fun defaultRecoveryExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "velocast-decoder-recovery").apply { isDaemon = true }
        }
    }
}
