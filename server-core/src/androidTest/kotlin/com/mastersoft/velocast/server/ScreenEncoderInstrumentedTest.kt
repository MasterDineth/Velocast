package com.mastersoft.velocast.server

import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.SeqTracker
import com.mastersoft.velocast.protocol.Status
import com.mastersoft.velocast.protocol.StatusKind
import com.mastersoft.velocast.protocol.VideoSize
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenEncoderInstrumentedTest {
    private class SyntheticSurfaceCaptureBackend(
        private val geometry: DisplayGeometry,
        private val logger: Logger
    ) : CaptureBackend {
        override fun queryDisplayGeometry(): DisplayGeometry = geometry

        override fun startCapture(surface: Surface, width: Int, height: Int): Closeable {
            val running = AtomicBoolean(true)
            val thread = Thread({
                val paint = Paint().apply { color = Color.WHITE }
                var frameIndex = 0
                while (running.get()) {
                    val posted = drawSyntheticFrame(surface, width, height, frameIndex, paint)
                    if (!posted) {
                        running.set(false)
                    } else {
                        frameIndex++
                        Thread.sleep(FRAME_INTERVAL_MS)
                    }
                }
            }, "synthetic-surface-feeder").apply {
                isDaemon = true
                start()
            }
            return Closeable {
                running.set(false)
                thread.join(JOIN_TIMEOUT_MS)
            }
        }

        private fun drawSyntheticFrame(
            surface: Surface,
            width: Int,
            height: Int,
            frameIndex: Int,
            paint: Paint
        ): Boolean = try {
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(if (frameIndex % 2 == 0) Color.DKGRAY else Color.BLUE)
                val x = ((frameIndex * STEP_PX) % (width - BAR_WIDTH).coerceAtLeast(1)).toFloat()
                canvas.drawRect(x, MARGIN_PX, x + BAR_WIDTH, height - MARGIN_PX, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            true
        } catch (e: IllegalStateException) {
            logger.debug(TAG, "Synthetic surface stopped", e)
            false
        } catch (e: Surface.OutOfResourcesException) {
            logger.warn(TAG, "Synthetic surface out of resources", e)
            false
        }
    }

    private class RecordingStats {
        var sawBitrateStatus = false
        var sawVideoSize = false
        var sawCodecConfig = false
        var totalVideoFrames = 0
        var prevCaptureUs = 0L
        var requestTriggered = false
        var framesAfterRequest = 0
        var idrWithinTwoFrames = false
    }

    @Test
    fun recordsFiveSecondsWithInitialIdrSpsPpsAndOnDemandKeyframeWithinTwoFrames() {
        val logger =
            Logger(minLevel = LogLevel.DEBUG, stderr = PrintStream(ByteArrayOutputStream()))
        val clock = AndroidServerClock()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, PIPE_CAPACITY_BYTES)

        val sink = SessionFrameSink(FrameWriter(pipeOut), pipeOut)
        sink.activateSession(1L, logger)
        val encoder = ScreenEncoder(
            initialDisplay = DisplayGeometry(TEST_WIDTH, TEST_HEIGHT, 0),
            frameSink = sink,
            clock = clock,
            logger = logger,
            options = StreamOptions(
                bitrateBps = TEST_BITRATE_BPS,
                fps = TEST_FPS,
                resolutionScale = 1.0f,
                bitrateMode = BitrateMode.CBR
            ),
            captureBackend = SyntheticSurfaceCaptureBackend(
                DisplayGeometry(TEST_WIDTH, TEST_HEIGHT, 0),
                logger
            )
        )

        val stats = RecordingStats()
        encoder.start()
        try {
            collectStreamForFiveSeconds(encoder, pipeIn, stats)
        } finally {
            encoder.close()
            sink.flushAndClose()
            pipeOut.close()
            pipeIn.close()
            logger.close()
        }

        assertTrue("Expected STATUS(BITRATE)", stats.sawBitrateStatus)
        assertTrue(
            "Expected at least 30 frames over 5s, got ${stats.totalVideoFrames}",
            stats.totalVideoFrames >= MIN_EXPECTED_FRAMES
        )
        assertTrue("Expected KEYFRAME_REQ trigger to fire", stats.requestTriggered)
        assertTrue(
            "Expected IDR within 2 frames after requestKeyframe " +
                "(saw after ${stats.framesAfterRequest})",
            stats.idrWithinTwoFrames
        )
    }

    private fun collectStreamForFiveSeconds(
        encoder: ScreenEncoder,
        pipeIn: PipedInputStream,
        stats: RecordingStats
    ) {
        val reader = FrameReader(pipeIn).apply { phase = Phase.SESSION }
        val header = FrameHeader()
        val seqTracker = SeqTracker()
        val startMs = System.currentTimeMillis()
        var eof = false
        while (!eof && System.currentTimeMillis() - startMs < RECORD_DURATION_MS) {
            if (!reader.readHeader(header)) {
                eof = true
            } else {
                seqTracker.onSeq(header.seq)
                val len = reader.readPayload(header)
                handleFrame(header, reader.payload, len, startMs, encoder, stats)
            }
        }
    }

    private fun handleFrame(
        header: FrameHeader,
        payload: ByteArray,
        len: Int,
        startMs: Long,
        encoder: ScreenEncoder,
        stats: RecordingStats
    ) {
        when (header.type) {
            MessageType.STATUS -> {
                val st = Status.decode(payload, 0, len)
                if (st.kind == StatusKind.BITRATE) {
                    stats.sawBitrateStatus = true
                    assertEquals(TEST_BITRATE_BPS, st.value)
                    assertTrue(st.detail.contains("mode="))
                }
            }

            MessageType.VIDEO_SIZE -> {
                val vs = VideoSize.decode(payload, 0, len)
                assertEquals(TEST_WIDTH, vs.streamWidth)
                assertEquals(TEST_HEIGHT, vs.streamHeight)
                stats.sawVideoSize = true
            }

            MessageType.CONFIG -> {
                val cfg = CodecConfig.decode(payload, 0, len)
                assertEquals(Codec.H264, cfg.codec)
                assertTrue(H264NalHelper.containsSpsAndPps(cfg.csd, 0, cfg.csd.size))
                stats.sawCodecConfig = true
            }

            MessageType.VIDEO -> handleVideoFrame(header, payload, len, startMs, encoder, stats)

            else -> Unit
        }
    }

    private fun handleVideoFrame(
        header: FrameHeader,
        payload: ByteArray,
        len: Int,
        startMs: Long,
        encoder: ScreenEncoder,
        stats: RecordingStats
    ) {
        stats.totalVideoFrames++
        assertTrue("Expected VIDEO_SIZE before VIDEO", stats.sawVideoSize)
        assertTrue("Expected CONFIG before VIDEO", stats.sawCodecConfig)
        assertTrue("Expected monotonic captureUs", header.captureUs >= stats.prevCaptureUs)
        assertTrue("Expected positive captureUs", header.captureUs > 0L)
        stats.prevCaptureUs = header.captureUs

        val isKey = (header.flags and FrameFlags.KEYFRAME) != 0
        if (stats.totalVideoFrames == 1) {
            assertTrue("First frame must carry KEYFRAME flag", isKey)
            assertTrue(
                "First IDR must contain SPS and PPS",
                H264NalHelper.containsSpsAndPps(payload, 0, len)
            )
            assertTrue(
                "First IDR must contain IDR slice",
                H264NalHelper.containsIdr(payload, 0, len)
            )
        }

        if (!stats.requestTriggered &&
            System.currentTimeMillis() - startMs >= KEYFRAME_TRIGGER_MS
        ) {
            stats.requestTriggered = true
            encoder.requestKeyframe()
        } else if (stats.requestTriggered && !stats.idrWithinTwoFrames) {
            stats.framesAfterRequest++
            if (isKey && H264NalHelper.containsIdr(payload, 0, len)) {
                stats.idrWithinTwoFrames = stats.framesAfterRequest <= MAX_KEYFRAME_WAIT_FRAMES
            }
        }
    }

    private companion object {
        const val TAG = "ScreenEncoderTest"
        const val TEST_WIDTH = 640
        const val TEST_HEIGHT = 480
        const val TEST_BITRATE_BPS = 2_000_000
        const val TEST_FPS = 30
        const val PIPE_CAPACITY_BYTES = 1024 * 1024
        const val RECORD_DURATION_MS = 5_000L
        const val KEYFRAME_TRIGGER_MS = 2_000L
        const val MIN_EXPECTED_FRAMES = 30
        const val MAX_KEYFRAME_WAIT_FRAMES = 2
        const val FRAME_INTERVAL_MS = 33L
        const val JOIN_TIMEOUT_MS = 1_000L
        const val STEP_PX = 17
        const val BAR_WIDTH = 40
        const val MARGIN_PX = 20f
    }
}
