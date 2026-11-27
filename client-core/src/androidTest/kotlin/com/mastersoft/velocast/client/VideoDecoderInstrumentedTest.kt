package com.mastersoft.velocast.client

import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.MessageType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoDecoderInstrumentedTest {

    @Test
    fun decodesRecorded320x240ThenRecreatesOn640x480ConfigAndSurvivesCorruptInputs() {
        val stream320 = parseH264Fixture(loadResource("h264/sample_320x240.h264"))
        val stream640 = parseH264Fixture(loadResource("h264/sample_640x480.h264"))
        val truncSps = loadResource("h264/truncated_sps.h264")
        val truncIdr = loadResource("h264/truncated_idr.h264")
        val corruptSps = loadResource("h264/corrupted_sps.h264")
        val corruptSlice = loadResource("h264/corrupted_slice.h264")

        val imageThread = HandlerThread("decoder-test-images").apply { start() }
        val imageHandler = Handler(imageThread.looper)
        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.PRIVATE, 4)
        val imageCount = AtomicInteger(0)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
            imageCount.incrementAndGet()
        }, imageHandler)

        val renderLatch320 = CountDownLatch(5)
        val renderLatch640 = CountDownLatch(5)
        val inSecondPhase = AtomicBoolean(false)
        val sizeChanges = mutableListOf<Pair<Int, Int>>()

        val decoder = VideoDecoder(
            initialWidth = 320,
            initialHeight = 240,
            onVideoSizeChanged = { w, h ->
                synchronized(sizeChanges) { sizeChanges += w to h }
            },
            onFrameRendered = {
                if (inSecondPhase.get()) renderLatch640.countDown() else renderLatch320.countDown()
            }
        )

        try {
            decoder.start(imageReader.surface)
            decoder.onCodecConfig(CodecConfig(Codec.H264, stream320.csd), FrameFlags.CONFIG)
            stream320.accessUnits.forEachIndexed { idx, (isKey, bytes) ->
                feedBytes(decoder, idx + 1, (idx + 1) * 33_333L, bytes, isKey)
                Thread.sleep(10L)
            }
            assertTrue(
                "Expected >= 5 frames rendered at 320x240",
                renderLatch320.await(5, TimeUnit.SECONDS)
            )

            // Inject truncated/corrupted payloads before switching resolution.
            decoder.onCodecConfig(CodecConfig(Codec.H264, truncSps), FrameFlags.CONFIG)
            decoder.onCodecConfig(CodecConfig(Codec.H264, corruptSps), FrameFlags.CONFIG)
            feedBytes(decoder, 20, 400_000L, truncIdr, isKey = true)
            feedBytes(decoder, 21, 433_333L, corruptSlice, isKey = false)
            Thread.sleep(50L)

            // Recreate cleanly on 640x480 CONFIG.
            inSecondPhase.set(true)
            val beforeRecreates = decoder.recreateCount
            decoder.onCodecConfig(CodecConfig(Codec.H264, stream640.csd), FrameFlags.CONFIG)
            assertTrue(
                "Expected codec recreation on 640x480 CONFIG",
                decoder.recreateCount > beforeRecreates
            )

            stream640.accessUnits.forEachIndexed { idx, (isKey, bytes) ->
                feedBytes(decoder, 30 + idx, 500_000L + idx * 33_333L, bytes, isKey)
                Thread.sleep(10L)
            }
            assertTrue(
                "Expected >= 5 frames rendered at 640x480",
                renderLatch640.await(5, TimeUnit.SECONDS)
            )
            assertTrue("Expected rendered images on ImageReader surface", imageCount.get() >= 10)
            assertTrue(
                "Decoder must remain Running after corrupt data",
                decoder.state.value is DecoderState.Running
            )
        } finally {
            decoder.stop()
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    @Test
    fun inducedOneSecondStallNeverGrowsLatencyAndRecoversWithin500Ms() = runBlocking {
        val width = 640
        val height = 480
        val framePool = FrameBufferPool(capacity = 16, initialBufferBytes = 256 * 1024)
        val metrics = SessionMetrics()

        val imageThread = HandlerThread("stall-test-images").apply { start() }
        val imageHandler = Handler(imageThread.looper)
        val imageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 4)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        }, imageHandler)

        val encoder = createLiveAvcEncoder(width, height)
        val inputSurface = encoder.createInputSurface()
        val syncRequests = AtomicInteger(0)
        val stallActive = AtomicBoolean(false)
        val stallEndedUs = AtomicLong(0L)
        val recoveryElapsedMs = AtomicLong(-1L)
        val awaitingPostStallRender = AtomicBoolean(false)

        val preStallE2eUs = mutableListOf<Long>()
        val postStallE2eUs = mutableListOf<Long>()
        val capturePhasePostStall = AtomicBoolean(false)

        val queue = VideoFrameQueue(
            capacity = 6,
            onRequestKeyframe = {
                syncRequests.incrementAndGet()
                requestSyncFrame(encoder)
            },
            onFrameDropped = { metrics.recordQueueDrop() },
            onFrameSkipped = { metrics.recordSkippedNonKeyframe() }
        )

        val decoder = VideoDecoder(
            initialWidth = width,
            initialHeight = height,
            metrics = metrics,
            onNeedKeyframe = {
                syncRequests.incrementAndGet()
                requestSyncFrame(encoder)
            },
            onFrameRendered = {
                if (awaitingPostStallRender.compareAndSet(true, false)) {
                    val elapsedMs = (System.nanoTime() / 1_000L - stallEndedUs.get()) / 1_000L
                    recoveryElapsedMs.set(elapsedMs)
                }
                metrics.state.value.latestFrame?.let { latest ->
                    val latency = latest.receiveQueueUs + latest.decodeUs + latest.renderUs
                    if (capturePhasePostStall.get()) {
                        synchronized(postStallE2eUs) { postStallE2eUs += latency }
                    } else if (!stallActive.get()) {
                        synchronized(preStallE2eUs) { preStallE2eUs += latency }
                    }
                }
            }
        )

        val producerRunning = AtomicBoolean(true)
        val encoderThread = Thread({
            drawLoopAndEncode(
                encoder,
                inputSurface,
                width,
                height,
                framePool,
                decoder,
                queue,
                producerRunning
            )
        }, "stall-test-encoder").apply { start() }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val consumerJob = scope.launch {
            while (true) {
                if (stallActive.get()) {
                    delay(20L)
                } else {
                    val frame = queue.receiveNext() ?: break
                    decoder.feedFrame(frame)
                }
            }
        }

        try {
            decoder.start(imageReader.surface)
            // Pre-stall warmup (~800 ms)
            delay(800L)

            // Induce 1-second consumer stall while 60fps encoder keeps pushing into capacity-6 DROP_OLDEST queue.
            stallActive.set(true)
            delay(1_000L)
            stallEndedUs.set(System.nanoTime() / 1_000L)
            awaitingPostStallRender.set(true)
            stallActive.set(false)

            // Allow recovery + post-stall steady state (~1000 ms).
            delay(300L)
            capturePhasePostStall.set(true)
            delay(700L)
        } finally {
            producerRunning.set(false)
            queue.close()
            consumerJob.cancel()
            scope.cancel()
            encoderThread.join(2_000L)
            decoder.stop()
            encoder.stop()
            encoder.release()
            inputSurface.release()
            imageReader.close()
            imageThread.quitSafely()
        }

        val stats = queue.stats()
        val preMedian = median(preStallE2eUs)
        val postMedian = median(postStallE2eUs)
        val recoveryMs = recoveryElapsedMs.get()
        Log.i(
            "VideoDecoderStallTest",
            "model=${android.os.Build.MODEL} preMedianUs=$preMedian postMedianUs=$postMedian " +
                "recoveryMs=$recoveryMs maxQueueDepth=${stats.maxObservedDepth} " +
                "dropped=${stats.droppedFrames} skipped=${stats.skippedNonKeyframes} " +
                "keyframeReqs=${syncRequests.get()} rendered=${metrics.state.value.renderedFrames}"
        )

        assertTrue(
            "Queue depth must stay <= 6, was ${stats.maxObservedDepth}",
            stats.maxObservedDepth <= 6
        )
        assertTrue("Expected dropped frames during 1s stall", stats.droppedFrames > 0L)
        assertTrue("Expected KEYFRAME_REQ on drops", syncRequests.get() > 0)
        assertTrue(
            "Expected post-stall recovery within 500ms, got ${recoveryMs}ms",
            recoveryMs in 0..500
        )
        assertTrue("Expected steady-state post-stall samples", postStallE2eUs.isNotEmpty())
        // Post-stall processing latency must stay bounded (no 1-second queue backlog growth).
        assertTrue(
            "Post-stall median ${postMedian}us grew excessively over pre-stall ${preMedian}us",
            postMedian < preMedian + 50_000L
        )
    }

    private fun drawLoopAndEncode(
        encoder: MediaCodec,
        inputSurface: Surface,
        width: Int,
        height: Int,
        pool: FrameBufferPool,
        decoder: VideoDecoder,
        queue: VideoFrameQueue,
        running: AtomicBoolean
    ) {
        encoder.start()
        val info = MediaCodec.BufferInfo()
        val paint = Paint().apply { color = Color.WHITE }
        var frameIdx = 0
        var lastDrawNs = 0L
        var cachedCsd: ByteArray? = null
        var seq = 1

        while (running.get()) {
            val nowNs = System.nanoTime()
            if (nowNs - lastDrawNs >= 16_000_000L) {
                lastDrawNs = nowNs
                try {
                    val canvas = inputSurface.lockCanvas(null)
                    try {
                        canvas.drawColor(if (frameIdx % 2 == 0) Color.DKGRAY else Color.BLUE)
                        canvas.drawRect(
                            (frameIdx * 11 % (width - 40)).toFloat(),
                            20f,
                            60f,
                            120f,
                            paint
                        )
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }
                    frameIdx++
                } catch (_: IllegalStateException) {
                    break
                }
            }
            val outIdx = encoder.dequeueOutputBuffer(info, 5_000L)
            if (outIdx >= 0) {
                val buf = encoder.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0) {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val raw = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(raw)
                    if (isConfig) {
                        cachedCsd = raw
                        decoder.onCodecConfig(CodecConfig(Codec.H264, raw), FrameFlags.CONFIG)
                    } else {
                        val csd = if (isKey) cachedCsd else null
                        val total = (csd?.size ?: 0) + raw.size
                        val pooled = pool.acquireVideoFrame(total)
                        var offset = 0
                        if (csd != null) {
                            csd.copyInto(pooled.payload, 0)
                            offset = csd.size
                        }
                        raw.copyInto(pooled.payload, offset)
                        val nowUs = System.nanoTime() / 1_000L
                        pooled.updateHeader(
                            type = MessageType.VIDEO,
                            flags = if (isKey) FrameFlags.KEYFRAME else 0,
                            seq = seq++,
                            ptsUs = info.presentationTimeUs,
                            captureUs = nowUs,
                            receivedAtUs = nowUs
                        )
                        queue.enqueue(pooled)
                    }
                }
                encoder.releaseOutputBuffer(outIdx, false)
            }
        }
    }

    private fun createLiveAvcEncoder(width: Int, height: Int): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return encoder
    }

    private fun requestSyncFrame(encoder: MediaCodec) {
        try {
            val b = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder.setParameters(b)
        } catch (_: IllegalStateException) {
            // Encoder already stopped.
        }
    }

    private fun feedBytes(
        decoder: VideoDecoder,
        seq: Int,
        ptsUs: Long,
        bytes: ByteArray,
        isKey: Boolean
    ) {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        decoder.feedFrame(
            ReceivedFrame(
                type = MessageType.VIDEO,
                flags = if (isKey) FrameFlags.KEYFRAME else 0,
                seq = seq,
                ptsUs = ptsUs,
                captureUs = nowUs,
                payload = bytes,
                payloadLength = bytes.size,
                receivedAtUs = nowUs
            )
        )
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun loadResource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }

    private data class ParsedH264Stream(
        val csd: ByteArray,
        val accessUnits: List<Pair<Boolean, ByteArray>>
    )

    private fun parseH264Fixture(annexB: ByteArray): ParsedH264Stream {
        val csdOut = ByteArrayOutputStream()
        val units = mutableListOf<Pair<Boolean, ByteArray>>()
        var pos = findStartCode(annexB, 0)
        while (pos >= 0 && pos < annexB.size) {
            val scLen = startCodeLen(annexB, pos)
            val nalStart = pos + scLen
            val next = findStartCode(annexB, nalStart)
            val nalEnd = if (next >= 0) next else annexB.size
            val nalType = annexB[nalStart].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                csdOut.write(0)
                csdOut.write(0)
                csdOut.write(0)
                csdOut.write(1)
                csdOut.write(annexB, nalStart, nalEnd - nalStart)
            } else if (nalType == 5 || nalType == 1) {
                val frameOut = ByteArrayOutputStream()
                frameOut.write(0)
                frameOut.write(0)
                frameOut.write(0)
                frameOut.write(1)
                frameOut.write(annexB, nalStart, nalEnd - nalStart)
                units += (nalType == 5) to frameOut.toByteArray()
            }
            pos = next
        }
        return ParsedH264Stream(csdOut.toByteArray(), units)
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte() ||
                    (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte())
                ) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun startCodeLen(data: ByteArray, index: Int): Int =
        if (index + 4 <= data.size && data[index + 2] == 0.toByte() &&
            data[index + 3] == 1.toByte()
        ) {
            4
        } else {
            3
        }
}
