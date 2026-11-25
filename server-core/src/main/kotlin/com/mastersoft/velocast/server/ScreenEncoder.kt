package com.mastersoft.velocast.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.Validators
import com.mastersoft.velocast.protocol.VideoSize
import com.mastersoft.velocast.server.platform.VirtualDisplayBridge
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Hardware H.264/AVC encoder pipeline backed by `MediaCodec` and [VirtualDisplayBridge].
 * Synchronizes lifecycle state via [stateLock] instead of unsynchronized flags (CQ-04).
 */
class ScreenEncoder(
    initialDisplay: DisplayGeometry,
    private val frameSink: SessionFrameSink,
    private val clock: ServerClock,
    private val logger: Logger,
    bitrateBps: Int = DEFAULT_BITRATE_BPS,
    fps: Int = DEFAULT_FPS,
    resolutionScale: Float = DEFAULT_SCALE
) : MediaPipeline {
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private var displayGeometry: DisplayGeometry = initialDisplay
    private var bitrateBps: Int = Validators.bitrateBps(bitrateBps)
    private var fps: Int = Validators.fps(fps.toFloat()).toInt()
    private var resolutionScale: Float = Validators.scale(resolutionScale)

    private var started = false
    private var stopped = false
    private var paused = false
    private var reconfigureRequested = false
    private var activeCodec: MediaCodec? = null
    private var workerThread: Thread? = null

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
    }

    override fun resume() {
        stateLock.withLock {
            if (stopped || !paused) return
            paused = false
            stateChanged.signalAll()
        }
    }

    override fun requestKeyframe() {
        val codec = stateLock.withLock { activeCodec } ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Keyframe request failed", e)
        }
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
                val wasReconfigure = stateLock.withLock {
                    val requested = reconfigureRequested
                    reconfigureRequested = false
                    requested
                }
                isReset = true
                if (wasReconfigure) {
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
        runSingleCodecPass(config, sendReset)
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

    private fun runSingleCodecPass(config: EncoderConfig, sendReset: Boolean) {
        val displayBridge = VirtualDisplayBridge(logger)
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null

        try {
            val liveDisplay = displayBridge.queryDefaultDisplayGeometry()
            stateLock.withLock { displayGeometry = liveDisplay }
            val encodeWidth =
                EncoderCodecHelpers.alignTo16((liveDisplay.width * config.scale).roundToInt())
            val encodeHeight =
                EncoderCodecHelpers.alignTo16((liveDisplay.height * config.scale).roundToInt())

            codec = EncoderCodecHelpers.createAndConfigure(
                encodeWidth,
                encodeHeight,
                config.bitrateBps,
                config.fps,
                logger
            )
            inputSurface = codec.createInputSurface()
            displayBridge.startMirroring(inputSurface, encodeWidth, encodeHeight)
            codec.start()
            stateLock.withLock { activeCodec = codec }

            logger.info(
                TAG,
                "Started H.264 encoder ${encodeWidth}x$encodeHeight @ ${config.fps}fps, " +
                    "${config.bitrateBps} bps"
            )
            drainLoop(codec, liveDisplay, encodeWidth, encodeHeight, sendReset)
        } finally {
            stateLock.withLock { activeCodec = null }
            displayBridge.close()
            EncoderCodecHelpers.releaseResources(codec, inputSurface, logger)
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
        val header = FrameHeader()
        val state = DrainPassState(
            frameBuffer = ByteArray(ProtocolV2.FRAME_HEADER_SIZE + INITIAL_FRAME_CAPACITY)
        )

        while (stateLock.withLock { !stopped && !paused && !reconfigureRequested }) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex >= 0) {
                state.emitOutputBuffer(
                    codec = codec,
                    outputIndex = outputIndex,
                    bufferInfo = bufferInfo,
                    display = display,
                    encodeWidth = encodeWidth,
                    encodeHeight = encodeHeight,
                    sendReset = sendReset,
                    header = header,
                    frameSink = frameSink,
                    clock = clock
                )
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                logger.debug(TAG, "Encoder output format changed: ${codec.outputFormat}")
            }
        }
    }

    private class DrainPassState(
        var frameBuffer: ByteArray,
        var sentConfig: Boolean = false,
        var sentFirstFrame: Boolean = false
    ) {
        fun emitOutputBuffer(
            codec: MediaCodec,
            outputIndex: Int,
            bufferInfo: MediaCodec.BufferInfo,
            display: DisplayGeometry,
            encodeWidth: Int,
            encodeHeight: Int,
            sendReset: Boolean,
            header: FrameHeader,
            frameSink: SessionFrameSink,
            clock: ServerClock
        ) {
            val outputBuffer = codec.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isConfig) {
                    val csd = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(csd)
                    val cfgFlags = if (sendReset) {
                        FrameFlags.CONFIG or FrameFlags.RESET
                    } else {
                        FrameFlags.CONFIG
                    }
                    frameSink.writeMessage(CodecConfig(Codec.H264, csd), cfgFlags)
                    frameSink.writeMessage(VideoSize(encodeWidth, encodeHeight, display))
                    sentConfig = true
                } else if (sentConfig) {
                    ensureCapacity(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(frameBuffer, ProtocolV2.FRAME_HEADER_SIZE, bufferInfo.size)

                    val resetBit = if (sendReset && !sentFirstFrame && isKey) {
                        FrameFlags.RESET
                    } else {
                        0
                    }
                    header.type = MessageType.VIDEO
                    header.flags = (if (isKey) FrameFlags.KEYFRAME else 0) or resetBit
                    header.ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                    header.captureUs = clock.monotonicMicros()
                    frameSink.writeVideoInPlace(header, frameBuffer, bufferInfo.size)
                    sentFirstFrame = true
                }
            }
        }

        private fun ensureCapacity(payloadSize: Int) {
            val needed = ProtocolV2.FRAME_HEADER_SIZE + payloadSize
            if (frameBuffer.size < needed) {
                frameBuffer = ByteArray(needed)
            }
        }
    }

    private data class EncoderConfig(
        val display: DisplayGeometry,
        val bitrateBps: Int,
        val fps: Int,
        val scale: Float
    )

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val DEFAULT_BITRATE_BPS = 8_000_000
        private const val DEFAULT_FPS = 60
        private const val DEFAULT_SCALE = 1.0f
        private const val SCALE_EPSILON = 0.02f
        private const val DEQUEUE_TIMEOUT_US = 50_000L
        private const val INITIAL_FRAME_CAPACITY = 256 * 1024
        private val nextId = AtomicInteger(1)
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

private object EncoderCodecHelpers {
    private const val TAG = "ScreenEncoder"
    private const val I_FRAME_INTERVAL_SEC = 5
    private const val IDLE_REPEAT_US = 250_000L
    private const val ALIGN_16_MASK = 15
    private const val ALIGN_16_MIN = 16

    fun alignTo16(value: Int): Int = (value and ALIGN_16_MASK.inv()).coerceAtLeast(ALIGN_16_MIN)

    fun createAndConfigure(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int,
        logger: Logger
    ): MediaCodec {
        val primary = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val fullFormat = buildVideoFormat(
                encodeWidth,
                encodeHeight,
                bitrate,
                frameRate,
                includeOptionalKeys = true
            )
            primary.configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return primary
        } catch (e: MediaCodec.CodecException) {
            logger.warn(TAG, "Optional encoder keys rejected; falling back to base format", e)
        } catch (e: IllegalArgumentException) {
            logger.warn(TAG, "Optional encoder format invalid; falling back to base format", e)
        }
        try {
            primary.release()
        } catch (e: IllegalStateException) {
            logger.warn(TAG, "Error releasing faulted primary encoder", e)
        }

        val fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val safeFormat = buildVideoFormat(
            encodeWidth,
            encodeHeight,
            bitrate,
            frameRate,
            includeOptionalKeys = false
        )
        fallback.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return fallback
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

    private fun buildVideoFormat(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int,
        includeOptionalKeys: Boolean
    ): MediaFormat = MediaFormat.createVideoFormat(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        encodeWidth,
        encodeHeight
    ).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, IDLE_REPEAT_US)

        if (includeOptionalKeys) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }
}
