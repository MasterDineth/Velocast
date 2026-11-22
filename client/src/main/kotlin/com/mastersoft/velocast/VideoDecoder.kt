package com.mastersoft.velocast

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-copy hardware H.264/AVC decoder rendering directly onto a SurfaceView's Surface.
 *
 * All MediaCodec calls (input queueing, output draining, and setOutputSurface) are serialized
 * under a single lock while MirrorClient's TCP reader runs asynchronously on its own IO coroutine
 * via incomingNalQueue—delivering zero TCP backpressure AND zero MediaCodec threading races.
 */
class VideoDecoder(
    private var surface: Surface,
    private val initialWidth: Int = 1080,
    private val initialHeight: Int = 2400,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onFrameRendered: () -> Unit = {}
) {
    private val codecLock = Any()
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val started = AtomicBoolean(false)
    private var renderedFrames = 0L

    @Volatile
    var surfaceValid = true

    fun start() {
        synchronized(codecLock) {
            if (started.get()) return
            Log.d("VideoDecoder", "Starting MediaCodec decoder ${initialWidth}x${initialHeight}")
            val decoder = createAndConfigureDecoder(surface, initialWidth, initialHeight)
            decoder.start()
            codec = decoder
            surfaceValid = true
            renderedFrames = 0L
            started.set(true)
            Log.d("VideoDecoder", "MediaCodec decoder started cleanly")
        }
    }

    private fun createAndConfigureDecoder(targetSurface: Surface, w: Int, h: Int): MediaCodec {
        val primary = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            primary.configure(format, targetSurface, null, 0)
            return primary
        } catch (t: Throwable) {
            Log.w("VideoDecoder", "Low-latency decoder config failed (${t.message}); using standard config")
            try { primary.release() } catch (_: Throwable) {}
        }

        val fallback = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val safeFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        }
        fallback.configure(safeFormat, targetSurface, null, 0)
        return fallback
    }

    fun setOutputSurface(newSurface: Surface) {
        synchronized(codecLock) {
            this.surface = newSurface
            this.surfaceValid = true
            val dec = codec ?: return
            if (started.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    dec.setOutputSurface(newSurface)
                    Log.d("VideoDecoder", "Attached new Surface via setOutputSurface")
                } catch (t: Throwable) {
                    Log.e("VideoDecoder", "Failed to setOutputSurface", t)
                }
            }
        }
    }

    /**
     * Queues a single H.264 NAL unit and drains all available decoded frames immediately.
     */
    fun feedNalUnit(ptsUs: Long, payload: ByteArray, length: Int) {
        synchronized(codecLock) {
            val decoder = codec ?: return
            if (!started.get() || length <= 0) return

            drainAvailableOutputsLocked(decoder)

            val isConfig = ptsUs == Protocol.PTS_CODEC_CONFIG
            val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            val presentationTimeUs = if (isConfig) 0L else ptsUs

            try {
                var inputIndex = decoder.dequeueInputBuffer(15_000L)
                if (inputIndex < 0 && (isConfig || length > 30_000)) {
                    drainAvailableOutputsLocked(decoder)
                    inputIndex = decoder.dequeueInputBuffer(25_000L)
                }

                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val copyLen = length.coerceAtMost(inputBuffer.remaining())
                        inputBuffer.put(payload, 0, copyLen)
                        decoder.queueInputBuffer(inputIndex, 0, copyLen, presentationTimeUs, flags)
                    }
                } else {
                    Log.w("VideoDecoder", "Dropped NAL unit (dequeue timeout, isConfig=$isConfig, size=$length)")
                }
                drainAvailableOutputsLocked(decoder)
            } catch (t: IllegalStateException) {
                if (started.get()) {
                    Log.w("VideoDecoder", "Decoder state error: ${t.message}")
                }
            }
        }
    }

    private fun drainAvailableOutputsLocked(decoder: MediaCodec) {
        while (started.get()) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outputIndex >= 0 -> {
                    val render = surfaceValid
                    decoder.releaseOutputBuffer(outputIndex, render)
                    if (render) {
                        renderedFrames++
                        if (renderedFrames == 1L) {
                            Log.d("VideoDecoder", "First video frame rendered to Surface!")
                        }
                        onFrameRendered()
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = decoder.outputFormat
                    Log.d("VideoDecoder", "Decoder output format changed: $fmt")
                    val width = resolveCroppedWidth(fmt)
                    val height = resolveCroppedHeight(fmt)
                    if (width > 0 && height > 0) {
                        onVideoSizeChanged(width, height)
                    }
                }

                else -> break
            }
        }
    }

    fun stop() {
        started.set(false)
        synchronized(codecLock) {
            val dec = codec
            codec = null
            if (dec != null) {
                try { dec.stop() } catch (_: Throwable) {}
                try { dec.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun resolveCroppedWidth(fmt: MediaFormat): Int {
        return if (fmt.containsKey("crop-left") && fmt.containsKey("crop-right")) {
            fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_WIDTH)) {
            fmt.getInteger(MediaFormat.KEY_WIDTH)
        } else {
            initialWidth
        }
    }

    private fun resolveCroppedHeight(fmt: MediaFormat): Int {
        return if (fmt.containsKey("crop-top") && fmt.containsKey("crop-bottom")) {
            fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
        } else if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
            fmt.getInteger(MediaFormat.KEY_HEIGHT)
        } else {
            initialHeight
        }
    }
}
