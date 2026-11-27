package com.mastersoft.velocast.client

import android.media.MediaCodec
import android.view.Surface
import com.mastersoft.velocast.protocol.Codec
import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.MessageType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderTest {

    @Test
    fun `H264SpsParser parses recorded 320x240 and 640x480 SPS and rejects corrupt data`() {
        val stream320 = loadResource("h264/sample_320x240.h264")
        val stream640 = loadResource("h264/sample_640x480.h264")
        val truncSps = loadResource("h264/truncated_sps.h264")
        val corruptSps = loadResource("h264/corrupted_sps.h264")

        assertEquals(StreamDimensions(320, 240), H264SpsParser.parseDimensions(stream320))
        assertEquals(StreamDimensions(640, 480), H264SpsParser.parseDimensions(stream640))
        assertNull(H264SpsParser.parseDimensions(truncSps))
        assertNull(H264SpsParser.parseDimensions(corruptSps))
        assertNull(H264SpsParser.parseDimensions(ByteArray(0)))
        assertNull(H264SpsParser.parseDimensions(byteArrayOf(0, 0, 0, 1, 0x67, 0x42)))
    }

    @Test
    fun `async callbacks, adaptive max bounds, CONFIG recreation, and dedicated render thread`() {
        val factory = FakeDecoderCodecFactory()
        val renderedThreads = CopyOnWriteArrayList<String>()
        val renderLatch320 = CountDownLatch(2)
        val renderLatch640 = CountDownLatch(2)
        var secondPhase = false
        val sizeChanges = CopyOnWriteArrayList<Pair<Int, Int>>()
        val metrics = SessionMetrics()

        val decoder = VideoDecoder(
            codecFactory = factory,
            initialWidth = 320,
            initialHeight = 240,
            maxAdaptiveWidth = 2560,
            maxAdaptiveHeight = 2560,
            metrics = metrics,
            recoveryExecutor = Executor { it.run() },
            onVideoSizeChanged = { w, h -> sizeChanges += w to h },
            onFrameRendered = {
                renderedThreads += Thread.currentThread().name
                if (secondPhase) renderLatch640.countDown() else renderLatch320.countDown()
            }
        )

        val stream320 = parseH264Fixture(loadResource("h264/sample_320x240.h264"))
        val stream640 = parseH264Fixture(loadResource("h264/sample_640x480.h264"))

        decoder.start(dummySurface())
        val firstAdapter = factory.instances[0]
        verifyInitial320Pass(decoder, firstAdapter, stream320)
        assertTrue(renderLatch320.await(3, TimeUnit.SECONDS))

        // New CONFIG carrying 640x480 SPS must recreate the codec at 640x480.
        secondPhase = true
        decoder.onCodecConfig(CodecConfig(Codec.H264, stream640.csd), FrameFlags.CONFIG)
        assertEquals(2, factory.instances.size)
        assertTrue(firstAdapter.released)
        assertTrue(sizeChanges.contains(640 to 480))
        val secondAdapter = factory.instances[1]
        assertEquals(640, secondAdapter.configuredFormat!!.width)
        assertEquals(480, secondAdapter.configuredFormat!!.height)

        secondAdapter.offerInputBuffer(0) // new CONFIG
        decoder.feedFrame(videoFrame(3, 67_000L, stream640.accessUnits[0].second, isKey = true))
        decoder.feedFrame(videoFrame(4, 100_000L, stream640.accessUnits[1].second, isKey = false))
        secondAdapter.offerInputBuffer(1)
        secondAdapter.offerInputBuffer(2)
        secondAdapter.emitOutputBuffer(0, 67_000L, 0, stream640.accessUnits[0].second.size)
        secondAdapter.emitOutputBuffer(1, 100_000L, 0, stream640.accessUnits[1].second.size)

        assertTrue(renderLatch640.await(3, TimeUnit.SECONDS))
        assertTrue(renderedThreads.all { it == "velocast-decoder-render" })
        assertEquals(4L, metrics.state.value.renderedFrames)
        decoder.stop()
    }

    private fun verifyInitial320Pass(
        decoder: VideoDecoder,
        adapter: FakeDecoderCodecAdapter,
        stream320: ParsedH264Stream
    ) {
        val initialCfg = adapter.configuredFormat!!
        assertEquals(320, initialCfg.width)
        assertEquals(240, initialCfg.height)
        assertEquals(2560, initialCfg.maxWidth)
        assertEquals(2560, initialCfg.maxHeight)

        decoder.onCodecConfig(CodecConfig(Codec.H264, stream320.csd), FrameFlags.CONFIG)
        adapter.offerInputBuffer(0)
        assertEquals(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, adapter.queuedInputs.last().flags)

        decoder.feedFrame(videoFrame(1, 1_000L, stream320.accessUnits[0].second, isKey = true))
        decoder.feedFrame(videoFrame(2, 34_000L, stream320.accessUnits[1].second, isKey = false))
        adapter.offerInputBuffer(1)
        adapter.offerInputBuffer(2)
        adapter.emitOutputBuffer(0, 1_000L, 0, stream320.accessUnits[0].second.size)
        adapter.emitOutputBuffer(1, 34_000L, 0, stream320.accessUnits[1].second.size)
    }

    @Test
    fun `survives truncated and corrupted H264 resources without freezing`() {
        val factory = FakeDecoderCodecFactory()
        val renderLatch = CountDownLatch(1)
        val decoder = VideoDecoder(
            codecFactory = factory,
            initialWidth = 320,
            initialHeight = 240,
            recoveryExecutor = Executor { it.run() },
            onFrameRendered = { renderLatch.countDown() }
        )

        decoder.start(dummySurface())
        val adapter = factory.instances.single()

        val valid320 = parseH264Fixture(loadResource("h264/sample_320x240.h264"))
        val truncIdr = loadResource("h264/truncated_idr.h264")
        val corruptSlice = loadResource("h264/corrupted_slice.h264")
        val corruptSps = loadResource("h264/corrupted_sps.h264")

        // Feed corrupt SPS config and truncated/corrupted frames; decoder stays Running and responsive.
        decoder.onCodecConfig(CodecConfig(Codec.H264, corruptSps), FrameFlags.CONFIG)
        decoder.feedFrame(videoFrame(1, 100L, truncIdr, isKey = true))
        decoder.feedFrame(videoFrame(2, 200L, corruptSlice, isKey = false))
        repeat(3) { idx -> adapter.offerInputBuffer(idx) }

        // Now feed clean CONFIG + IDR and verify normal rendering continues.
        decoder.onCodecConfig(CodecConfig(Codec.H264, valid320.csd), FrameFlags.CONFIG)
        decoder.feedFrame(videoFrame(3, 300L, valid320.accessUnits[0].second, isKey = true))
        adapter.offerInputBuffer(3)
        adapter.offerInputBuffer(4)
        adapter.emitOutputBuffer(0, 300L, 0, valid320.accessUnits[0].second.size)

        assertTrue(renderLatch.await(2, TimeUnit.SECONDS))
        assertTrue(decoder.state.value is DecoderState.Running)
        decoder.stop()
    }

    @Test
    fun `recovers from CodecExceptions and fails loudly when exhausted`() {
        val factory = FakeDecoderCodecFactory()
        val keyframeRequests = AtomicInteger(0)
        val fatalErrors = CopyOnWriteArrayList<SessionError>()
        val metrics = SessionMetrics()

        val decoder = VideoDecoder(
            codecFactory = factory,
            initialWidth = 320,
            initialHeight = 240,
            maxConsecutiveRecoveryAttempts = 2,
            metrics = metrics,
            recoveryExecutor = Executor { it.run() },
            onNeedKeyframe = { keyframeRequests.incrementAndGet() },
            onFatalError = { fatalErrors += it }
        )

        val valid320 = parseH264Fixture(loadResource("h264/sample_320x240.h264"))
        decoder.start(dummySurface())
        decoder.onCodecConfig(CodecConfig(Codec.H264, valid320.csd), FrameFlags.CONFIG)

        val firstAdapter = factory.instances[0]

        // 1. Transient error -> flush + restart + request keyframe.
        firstAdapter.registeredCallback!!.onError(
            CodecFailure(isRecoverable = false, isTransient = true, diagnosticInfo = "transient")
        )
        assertTrue(firstAdapter.flushed)
        assertEquals(1, keyframeRequests.get())
        assertTrue(decoder.state.value is DecoderState.Running)

        // 2. Recoverable error -> reset + reconfigure + start + re-queue CONFIG + request keyframe.
        firstAdapter.registeredCallback!!.onError(
            CodecFailure(isRecoverable = true, isTransient = false, diagnosticInfo = "recoverable")
        )
        assertTrue(firstAdapter.resetCalled)
        assertEquals(2, keyframeRequests.get())
        assertTrue(decoder.state.value is DecoderState.Running)

        // Successful render resets consecutiveFailures counter.
        firstAdapter.offerInputBuffer(0) // cached CONFIG
        firstAdapter.emitOutputBuffer(0, 100L, 0, 32)
        Thread.sleep(50L)

        // 3. Non-recoverable error -> recreate new codec instance + request keyframe.
        firstAdapter.registeredCallback!!.onError(
            CodecFailure(isRecoverable = false, isTransient = false, diagnosticInfo = "fatal-hw")
        )
        assertEquals(2, factory.instances.size)
        assertTrue(firstAdapter.released)
        assertEquals(3, keyframeRequests.get())
        assertTrue(decoder.state.value is DecoderState.Running)

        // 4. Exhaust maxConsecutiveRecoveryAttempts -> enters DecoderState.Failed and notifies onFatalError.
        val secondAdapter = factory.instances[1]
        secondAdapter.registeredCallback!!.onError(
            CodecFailure(isRecoverable = false, isTransient = false, diagnosticInfo = "err-2")
        )
        val thirdAdapter = factory.instances[2]
        thirdAdapter.registeredCallback!!.onError(
            CodecFailure(isRecoverable = false, isTransient = false, diagnosticInfo = "err-3")
        )

        val failedState = decoder.state.value
        assertTrue(failedState is DecoderState.Failed)
        assertEquals(1, fatalErrors.size)
        assertNotNull(fatalErrors.single())
        decoder.stop()
    }

    @Test
    fun `attachSurface skips setOutputSurface for already active surface`() {
        val factory = FakeDecoderCodecFactory()
        val decoder = VideoDecoder(
            codecFactory = factory,
            initialWidth = 320,
            initialHeight = 240,
            recoveryExecutor = Executor { it.run() }
        )

        val firstSurface = dummySurface()
        val secondSurface = dummySurface()

        decoder.start(firstSurface)
        val adapter = factory.instances.single()
        assertEquals(0, adapter.setOutputSurfaceCalls.get())

        // Attaching the exact same surface again while codec is active must be a no-op.
        decoder.attachSurface(firstSurface)
        assertEquals(0, adapter.setOutputSurfaceCalls.get())

        // Attaching a different surface delegates to setOutputSurface once.
        decoder.attachSurface(secondSurface)
        assertEquals(1, adapter.setOutputSurfaceCalls.get())

        // Attaching secondSurface again must also be a no-op.
        decoder.attachSurface(secondSurface)
        assertEquals(1, adapter.setOutputSurfaceCalls.get())

        decoder.stop()
    }

    @Test
    fun `stop shuts down default recoveryExecutor without shutting down caller executor`() {
        val factory = FakeDecoderCodecFactory()
        val defaultDecoder = VideoDecoder(codecFactory = factory)
        defaultDecoder.start(dummySurface())
        defaultDecoder.stop()

        val callerExecutor = Executors.newSingleThreadExecutor()
        try {
            val callerDecoder = VideoDecoder(
                codecFactory = factory,
                recoveryExecutor = callerExecutor
            )
            callerDecoder.start(dummySurface())
            callerDecoder.stop()
            assertFalse(callerExecutor.isShutdown)
        } finally {
            callerExecutor.shutdownNow()
        }
    }

    private fun videoFrame(seq: Int, ptsUs: Long, bytes: ByteArray, isKey: Boolean): ReceivedFrame =
        ReceivedFrame(
            type = MessageType.VIDEO,
            flags = if (isKey) FrameFlags.KEYFRAME else 0,
            seq = seq,
            ptsUs = ptsUs,
            captureUs = ptsUs,
            payload = bytes
        )

    private fun loadResource(path: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Missing test resource: $path")
        return stream.use { it.readBytes() }
    }

    private fun dummySurface(): Surface {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Surface::class.java) as Surface
    }

    private data class QueuedInputRecord(
        val index: Int,
        val size: Int,
        val ptsUs: Long,
        val flags: Int
    )

    private class FakeDecoderCodecAdapter : DecoderCodecAdapter {
        var registeredCallback: DecoderCodecCallback? = null
        var configuredFormat: DecoderFormatConfig? = null
        var started = false
        var flushed = false
        var resetCalled = false
        var released = false
        val setOutputSurfaceCalls = AtomicInteger(0)
        val queuedInputs = CopyOnWriteArrayList<QueuedInputRecord>()
        private val inputBuffers = HashMap<Int, ByteBuffer>()

        override fun setCallback(callback: DecoderCodecCallback) {
            registeredCallback = callback
        }

        override fun configure(config: DecoderFormatConfig, surface: Surface) {
            configuredFormat = config
        }

        override fun start() {
            started = true
        }

        override fun flush() {
            flushed = true
        }

        override fun reset() {
            resetCalled = true
        }

        override fun setOutputSurface(surface: Surface) {
            setOutputSurfaceCalls.incrementAndGet()
        }

        override fun getInputBuffer(index: Int): ByteBuffer =
            inputBuffers.getOrPut(index) { ByteBuffer.allocate(64 * 1024) }

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int
        ) {
            queuedInputs += QueuedInputRecord(index, size, presentationTimeUs, flags)
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit

        override fun stop() {
            started = false
        }

        override fun release() {
            released = true
        }

        fun offerInputBuffer(index: Int) {
            registeredCallback!!.onInputBufferAvailable(index)
        }

        fun emitOutputBuffer(index: Int, ptsUs: Long, flags: Int, size: Int) {
            registeredCallback!!.onOutputBufferAvailable(index, ptsUs, flags, size)
        }
    }

    private class FakeDecoderCodecFactory : DecoderCodecFactory {
        val instances = CopyOnWriteArrayList<FakeDecoderCodecAdapter>()

        override fun createAvcDecoder(): DecoderCodecAdapter =
            FakeDecoderCodecAdapter().also { instances += it }
    }

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
        var found = -1
        while (i + 3 <= data.size && found < 0) {
            val zeroPair = data[i] == 0.toByte() && data[i + 1] == 0.toByte()
            if (zeroPair && isStartCodeAt(data, i)) {
                found = i
            }
            i++
        }
        return found
    }

    private fun isStartCodeAt(data: ByteArray, index: Int): Boolean {
        val isShort = data[index + 2] == 1.toByte()
        val isLong = index + 4 <= data.size &&
            data[index + 2] == 0.toByte() &&
            data[index + 3] == 1.toByte()
        return isShort || isLong
    }

    private fun startCodeLen(data: ByteArray, index: Int): Int {
        val isLong = index + 4 <= data.size &&
            data[index + 2] == 0.toByte() &&
            data[index + 3] == 1.toByte()
        return if (isLong) 4 else 3
    }
}
