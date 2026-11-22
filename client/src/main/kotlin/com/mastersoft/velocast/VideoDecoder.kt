package com.mastersoft.velocast

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-copy hardware H.264/AVC decoder with a dedicated output drain thread.
 * Decoupling output rendering from input queuing prevents MediaCodec stalls from
 * causing TCP backpressure and Wi-Fi stream jitter.
 */
class VideoDecoder(
    private var surface: Surface,
    private val initialWidth: Int = 1080,
    private val initialHeight: Int = 2400,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onFrameRendered: () -> Unit = {}
) {
    private val lifecycleLock = Any()
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    var surfaceValid = true

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get()) return
            Log.d("VideoDecoder", "Starting MediaCodec decoder ${initialWidth}x${initialHeight}")
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                initialWidth,
                initialHeight
            ).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            surfaceValid = true
            running.set(true)

            outputThread = Thread({ drainOutputsLoop(decoder) }, "velocast-decoder-output").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
            Log.d("VideoDecoder", "MediaCodec decoder started with dedicated output drain thread")
        }
    }

    fun setOutputSurface(newSurface: Surface) {
        synchronized(lifecycleLock) {
            this.surface = newSurface
            this.surfaceValid = true
            val dec = codec ?: return
            if (running.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
     * Queues a single H.264 NAL unit into MediaCodec. Called from the dedicated decoder feeder coroutine.
     */
    fun feedNalUnit(ptsUs: Long, payload: ByteArray, length: Int) {
        val decoder = codec ?: return
        if (!running.get() || length <= 0) return

        val isConfig = ptsUs == Protocol.PTS_CODEC_CONFIG
        val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        val presentationTimeUs = if (isConfig) 0L else ptsUs

        try {
            val inputIndex = decoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val copyLen = length.coerceAtMost(inputBuffer.remaining())
                    inputBuffer.put(payload, 0, copyLen)
                    decoder.queueInputBuffer(inputIndex, 0, copyLen, presentationTimeUs, flags)
                }
            }
        } catch (t: IllegalStateException) {
            if (running.get()) {
                Log.w("VideoDecoder", "Decoder input state error: ${t.message}")
            }
        }
    }

    private fun drainOutputsLoop(decoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex >= 0 -> {
                        val render = surfaceValid
                        decoder.releaseOutputBuffer(outputIndex, render)
                        if (render) {
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
                }
            } catch (t: IllegalStateException) {
                if (running.get()) {
                    Log.w("VideoDecoder", "Decoder output state error: ${t.message}")
                }
                break
            }
        }
    }

    fun stop() {
        running.set(false)
        val threadToJoin: Thread?
        val decoderToRelease: MediaCodec?
        synchronized(lifecycleLock) {
            threadToJoin = outputThread
            outputThread = null
            decoderToRelease = codec
            codec = null
        }
        threadToJoin?.interrupt()
        try { threadToJoin?.join(400L) } catch (_: Throwable) {}
        if (decoderToRelease != null) {
            try { decoderToRelease.stop() } catch (_: Throwable) {}
            try { decoderToRelease.release() } catch (_: Throwable) {}
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
