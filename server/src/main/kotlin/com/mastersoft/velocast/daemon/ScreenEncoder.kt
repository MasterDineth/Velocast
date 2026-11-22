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
 * Key optimizations for older SoCs (e.g. Snapdragon 732G / 120Hz AMOLED):
 * - Caps SurfaceFlinger input cadence via KEY_MAX_FPS_TO_ENCODER (prevents 120Hz oversampling).
 * - Uses VBR bitrate mode and 10s I-frame interval to cut DSP heat on static UI screens.
 * - Sets KEY_REPEAT_PREVIOUS_FRAME_AFTER to 1s (1 FPS idle heartbeat instead of 10 FPS).
 * - Disables B-frames (KEY_MAX_B_FRAMES = 0) and enables KEY_LOW_LATENCY = 1.
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

            // Zero-flicker dynamic bitrate update when FPS and resolution are unchanged
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

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                configureEncoderWithFallback(codec, encodeWidth, encodeHeight, bitrateBps, fps)
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
                        "@ ${fps}fps (maxInputFps=$fps), ${bitrateBps / 1_000_000} Mbps"
                )

                drainEncoderLoop(codec, out, writeLock)
            } finally {
                activeCodec = null
                try { codec?.stop() } catch (_: Throwable) {}
                try { codec?.release() } catch (_: Throwable) {}
                try { inputSurface?.release() } catch (_: Throwable) {}
                displayBridge.close()
            }

            if (!reconfigureRequested.get()) {
                break
            }
        }
    }

    private fun drainEncoderLoop(codec: MediaCodec, out: OutputStream, writeLock: Any) {
        val bufferInfo = MediaCodec.BufferInfo()
        // Reusable coalesced frame buffer: [12-byte header][NAL payload]
        var frameBuffer = ByteArray(Protocol.VIDEO_HEADER_SIZE + 256 * 1024)

        while (running.get() && !reconfigureRequested.get()) {
            // 100ms timeout cuts idle CPU wakeups 5x compared to 20ms while keeping stop/reconfig snappy
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 100_000L)
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
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    println("[ScreenEncoder] Output format: ${codec.outputFormat}")
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Static screen; encoder is sleeping cleanly.
                }
            }
        }
    }

    private fun configureEncoderWithFallback(
        codec: MediaCodec,
        encodeWidth: Int,
        encodeHeight: Int,
        bitrate: Int,
        frameRate: Int
    ) {
        val fullFormat = buildVideoFormat(encodeWidth, encodeHeight, bitrate, frameRate, includeOptionalKeys = true)
        try {
            codec.configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            System.err.println("[ScreenEncoder] Full format rejected (${t.message}); retrying minimal format")
            val safeFormat = buildVideoFormat(encodeWidth, encodeHeight, bitrate, frameRate, includeOptionalKeys = false)
            codec.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
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
            // 10s I-frame interval (matches scrcpy) reduces DSP heat and Wi-Fi burst spikes
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // 1s idle repeat instead of 100ms cuts idle encoding workload by 10x
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L)

            if (includeOptionalKeys) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Crucial on 120Hz displays (e.g. Redmi Note 10 Pro): caps SurfaceFlinger input rate
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
        }
    }

    private fun alignTo16(value: Int): Int = (value and 15.inv()).coerceAtLeast(16)
}
