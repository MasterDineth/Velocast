package com.mastersoft.velocast.daemon

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Thermal- and latency-optimized hardware H.264/AVC encoder backed by MediaCodec.
 *
 * Reliability & thermal safeguards:
 * - Creates a fresh MediaCodec instance on format fallback (never reuses a faulted codec).
 * - Caps 120Hz AMOLED input rate via KEY_MAX_FPS_TO_ENCODER (with safe fallback).
 * - Uses 5s I-frame interval + 250ms idle frame repeat so static screens render immediately
 *   without overheating older SoCs.
 * - Writes header + NAL payload in a single coalesced socket write with zero per-frame allocations.
 */
class ScreenEncoder(
    private val baseWidth: Int,
    private val baseHeight: Int,
    @Volatile private var bitrateBps: Int = 8_000_000,
    @Volatile private var fps: Int = 60,
    @Volatile private var resolutionScale: Float = 1.0f
) {
    private val running = AtomicBoolean(false)
    private val reconfigureRequested = AtomicBoolean(false)
    @Volatile private var activeCodec: MediaCodec? = null

    fun applyInitialConfig(newBitrateBps: Int, newFps: Int, newScale: Float) {
        bitrateBps = newBitrateBps.coerceIn(1_000_000, 30_000_000)
        fps = newFps.coerceIn(15, 120)
        if (newScale > 0.1f) {
            resolutionScale = newScale.coerceIn(0.5f, 1.0f)
        }
    }

    fun requestReconfigure(newBitrateBps: Int, newFps: Int, newScale: Float = 0f) {
        val clampedBitrate = newBitrateBps.coerceIn(1_000_000, 30_000_000)
        val clampedFps = newFps.coerceIn(15, 120)
        val clampedScale = if (newScale > 0.1f) newScale.coerceIn(0.5f, 1.0f) else resolutionScale

        val fpsChanged = clampedFps != fps
        val bitrateChanged = clampedBitrate != bitrateBps
        val scaleChanged = kotlin.math.abs(clampedScale - resolutionScale) > 0.02f

        if (fpsChanged || bitrateChanged || scaleChanged) {
            bitrateBps = clampedBitrate
            fps = clampedFps
            resolutionScale = clampedScale

            if (!fpsChanged && !scaleChanged && activeCodec != null) {
                try {
                    val params = Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clampedBitrate)
                    }
                    activeCodec?.setParameters(params)
                    println("[ScreenEncoder] Dynamically updated bitrate to ${clampedBitrate / 1_000_000} Mbps")
                    return
                } catch (t: Throwable) {
                    System.err.println("[ScreenEncoder] Dynamic bitrate update unsupported (${t.message}); restarting encoder")
                }
            }

            reconfigureRequested.set(true)
            println(
                "[ScreenEncoder] Reconfigure requested: ${clampedBitrate / 1_000_000} Mbps @ " +
                    "${clampedFps}fps, scale=${(clampedScale * 100).roundToInt()}%"
            )
        }
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    fun streamTo(out: OutputStream, writeLock: Any) {
        running.set(true)

        while (running.get()) {
            reconfigureRequested.set(false)
            val displayBridge = VirtualDisplayBridge()
            var codec: MediaCodec? = null
            var inputSurface: Surface? = null

            try {
                val deviceDims = VirtualDisplayBridge.queryDefaultDisplaySize(baseWidth, baseHeight)
                val encodeWidth = alignTo16((deviceDims.width * resolutionScale).roundToInt())
                val encodeHeight = alignTo16((deviceDims.height * resolutionScale).roundToInt())

                codec = createAndConfigureEncoder(encodeWidth, encodeHeight, bitrateBps, fps)
                inputSurface = codec.createInputSurface()

                displayBridge.startMirroring(
                    targetSurface = inputSurface,
                    deviceWidth = deviceDims.width,
                    deviceHeight = deviceDims.height,
                    videoWidth = encodeWidth,
                    videoHeight = encodeHeight
                )

                codec.start()
                activeCodec = codec
                println(
                    "[ScreenEncoder] Started H.264 encoder ${encodeWidth}x${encodeHeight} " +
                        "@ ${fps}fps, ${bitrateBps / 1_000_000} Mbps"
                )
                System.out.flush()

                drainEncoderLoop(codec, out, writeLock)
            } finally {
                activeCodec = null
                // Clean up VirtualDisplay FIRST so SurfaceFlinger disconnects from inputSurface
                displayBridge.close()
                try { codec?.stop() } catch (_: Throwable) {}
                try { codec?.release() } catch (_: Throwable) {}
                try { inputSurface?.release() } catch (_: Throwable) {}
            }

            if (!reconfigureRequested.get()) {
                break
            }
            // Settle pause before recreating VirtualDisplay on Android 16
            try { Thread.sleep(200L) } catch (_: InterruptedException) { break }
        }
    }

    private fun drainEncoderLoop(codec: MediaCodec, out: OutputStream, writeLock: Any) {
        val bufferInfo = MediaCodec.BufferInfo()
        var frameBuffer = ByteArray(Protocol.VIDEO_HEADER_SIZE + 256 * 1024)
        var sentFrames = 0L

        while (running.get() && !reconfigureRequested.get()) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000L)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val size = bufferInfo.size
                        val needed = Protocol.VIDEO_HEADER_SIZE + size
                        if (frameBuffer.size < needed) {
                            frameBuffer = ByteArray(needed)
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + size)
                        outputBuffer.get(frameBuffer, Protocol.VIDEO_HEADER_SIZE, size)

                        val isCodecConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val pts = if (isCodecConfig) {
                            Protocol.PTS_CODEC_CONFIG
                        } else {
                            bufferInfo.presentationTimeUs
                        }

                        synchronized(writeLock) {
                            Protocol.writeCoalescedFrame(out, pts, frameBuffer, size)
                            out.flush()
                        }

                        sentFrames++
                        if (sentFrames == 1L || sentFrames == 2L || sentFrames % 300L == 0L) {
                            println("[ScreenEncoder] Streamed packet #$sentFrames (size=$size, config=$isCodecConfig)")
                            System.out.flush()
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    println("[ScreenEncoder] Output format: ${codec.outputFormat}")
                    System.out.flush()
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Static screen; encoder idle.
                }
            }
        }
    }

    /**
     * Always creates a brand-new MediaCodec instance if optional keys are rejected,
     * avoiding the Android MediaCodec Error state that causes silent black output.
     */
    private fun createAndConfigureEncoder(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int
    ): MediaCodec {
        val primary = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val fullFormat = buildVideoFormat(encodeWidth, encodeHeight, bitrate, frameRate, includeOptionalKeys = true)
            primary.configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return primary
        } catch (t: Throwable) {
            System.err.println("[ScreenEncoder] Optional keys rejected (${t.message}); creating fresh safe encoder")
            try { primary.release() } catch (_: Throwable) {}
        }

        val fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val safeFormat = buildVideoFormat(encodeWidth, encodeHeight, bitrate, frameRate, includeOptionalKeys = false)
        fallback.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return fallback
    }

    private fun buildVideoFormat(
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int,
        includeOptionalKeys: Boolean
    ): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // 250ms idle repeat guarantees static screens appear immediately while staying cool
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)

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

    private fun alignTo16(value: Int): Int = (value and 15.inv()).coerceAtLeast(16)
}
