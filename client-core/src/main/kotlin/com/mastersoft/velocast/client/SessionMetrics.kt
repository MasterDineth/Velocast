package com.mastersoft.velocast.client

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-frame pipeline stage durations in microseconds (PERF-08). */
data class FrameStageTimings(
    val seq: Int,
    val ptsUs: Long,
    val captureUs: Long,
    val receiveQueueUs: Long,
    val decodeUs: Long,
    val renderUs: Long,
    val captureToReceiveUs: Long?,
    val endToEndUs: Long?,
    val clockOffsetUs: Long?,
    val renderedAtMs: Long
)

/** Structured event stored in [DiagnosticsRingBuffer] for the diagnostics screen. */
data class DiagnosticsLogEntry(val timestampMs: Long, val category: String, val message: String)

/** Immutable metrics snapshot exposed via [SessionMetrics.state]. */
data class SessionMetricsSnapshot(
    val latestFrame: FrameStageTimings? = null,
    val medianReceiveQueueUs: Long = 0L,
    val medianDecodeUs: Long = 0L,
    val medianRenderUs: Long = 0L,
    val medianEndToEndUs: Long? = null,
    val p95EndToEndUs: Long? = null,
    val clockOffsetUs: Long? = null,
    val minRttUs: Long? = null,
    val latestRttUs: Long? = null,
    val renderedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val skippedNonKeyframes: Long = 0L,
    val keyframeRequestsSent: Long = 0L,
    val codecRecoveries: Long = 0L,
    val fps: Float = 0f
)

/** Fixed-capacity circular diagnostics log buffer safe for concurrent writers/readers. */
class DiagnosticsRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val lock = ReentrantLock()
    private val entries = arrayOfNulls<DiagnosticsLogEntry>(capacity)
    private var head = 0
    private var count = 0

    fun append(timestampMs: Long, category: String, message: String) {
        lock.withLock {
            entries[head] = DiagnosticsLogEntry(timestampMs, category, message)
            head = (head + 1) % capacity
            if (count < capacity) count++
        }
    }

    fun snapshot(): List<DiagnosticsLogEntry> = lock.withLock {
        val result = ArrayList<DiagnosticsLogEntry>(count)
        val start = if (count < capacity) 0 else head
        for (i in 0 until count) {
            entries[(start + i) % capacity]?.let { result.add(it) }
        }
        result
    }

    fun clear() {
        lock.withLock {
            entries.fill(null)
            head = 0
            count = 0
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/**
 * Computes per-frame stage timings (`receiveQueueUs`, `decodeUs`, `renderUs`, and `endToEndUs`
 * using the min-RTT clock offset estimate from `PING`/`PONG`), exposing them via [state] and a
 * [DiagnosticsRingBuffer] (PERF-08).
 */
class SessionMetrics(
    private val clock: SessionClock = SystemSessionClock,
    logCapacity: Int = DiagnosticsRingBuffer.DEFAULT_CAPACITY
) {
    private class PingSample(var rttUs: Long = Long.MAX_VALUE, var offsetUs: Long = 0L)

    private class RollingLongWindow(private val capacity: Int) {
        private val values = LongArray(capacity)
        private val scratch = LongArray(capacity)
        private var head = 0
        private var count = 0

        fun add(value: Long) {
            values[head] = value
            head = (head + 1) % capacity
            if (count < capacity) count++
        }

        fun clear() {
            head = 0
            count = 0
        }

        fun percentile(percent: Int): Long? {
            if (count == 0) return null
            for (i in 0 until count) {
                scratch[i] = values[i]
            }
            scratch.sort(0, count)
            val index = ((count - 1) * percent) / PERCENT_SCALE
            return scratch[index]
        }
    }

    private val lock = ReentrantLock()
    private val mutableState = MutableStateFlow(SessionMetricsSnapshot())
    val state: StateFlow<SessionMetricsSnapshot> = mutableState.asStateFlow()
    val diagnosticsLog = DiagnosticsRingBuffer(logCapacity)

    private val pingSamples = Array(PING_WINDOW_SIZE) { PingSample() }
    private var pingCursor = 0
    private var pingCount = 0
    private var bestClockOffsetUs: Long? = null
    private var minRttUs: Long? = null
    private var latestRttUs: Long? = null

    private val receiveQueueWindow = RollingLongWindow(STAGE_WINDOW_SIZE)
    private val decodeWindow = RollingLongWindow(STAGE_WINDOW_SIZE)
    private val renderWindow = RollingLongWindow(STAGE_WINDOW_SIZE)
    private val endToEndWindow = RollingLongWindow(STAGE_WINDOW_SIZE)
    private val renderTimeWindowMs = LongArray(FPS_WINDOW_SIZE)
    private var renderTimeHead = 0
    private var renderTimeCount = 0

    private var latestFrame: FrameStageTimings? = null
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var skippedNonKeyframes = 0L
    private var keyframeRequestsSent = 0L
    private var codecRecoveries = 0L
    private var currentFps = 0f

    fun recordPong(t0Us: Long, t1Us: Long, serverUs: Long) {
        val rttUs = (t1Us - t0Us).coerceAtLeast(0L)
        val offsetUs = serverUs - (t0Us + t1Us) / 2L
        var improvedSync = false
        val nowMs = clock.monotonicMillis()

        lock.withLock {
            val sample = pingSamples[pingCursor]
            sample.rttUs = rttUs
            sample.offsetUs = offsetUs
            pingCursor = (pingCursor + 1) % PING_WINDOW_SIZE
            if (pingCount < PING_WINDOW_SIZE) pingCount++

            var bestIdx = 0
            var bestRtt = Long.MAX_VALUE
            for (i in 0 until pingCount) {
                if (pingSamples[i].rttUs < bestRtt) {
                    bestRtt = pingSamples[i].rttUs
                    bestIdx = i
                }
            }
            val prevMin = minRttUs
            minRttUs = bestRtt
            latestRttUs = rttUs
            bestClockOffsetUs = pingSamples[bestIdx].offsetUs
            improvedSync = prevMin == null || rttUs <= prevMin
            publishLocked()
        }
        if (improvedSync) {
            diagnosticsLog.append(
                nowMs,
                CATEGORY_CLOCK_SYNC,
                "minRttUs=$rttUs offsetUs=$offsetUs"
            )
        }
    }

    fun recordFrameRendered(
        seq: Int,
        ptsUs: Long,
        captureUs: Long,
        receivedAtUs: Long,
        dequeuedForDecodeUs: Long,
        decodedAtUs: Long,
        renderedAtUs: Long
    ) {
        val receiveQueueUs = (dequeuedForDecodeUs - receivedAtUs).coerceAtLeast(0L)
        val decodeUs = (decodedAtUs - dequeuedForDecodeUs).coerceAtLeast(0L)
        val renderUs = (renderedAtUs - decodedAtUs).coerceAtLeast(0L)
        val renderedAtMs = renderedAtUs / MICROS_PER_MILLI

        val timings = lock.withLock {
            val offset = bestClockOffsetUs
            val captureToReceiveUs = if (offset != null && captureUs > 0L) {
                (receivedAtUs - (captureUs - offset)).coerceAtLeast(0L)
            } else {
                null
            }
            val endToEndUs = if (offset != null && captureUs > 0L) {
                (renderedAtUs - (captureUs - offset)).coerceAtLeast(0L)
            } else {
                null
            }

            receiveQueueWindow.add(receiveQueueUs)
            decodeWindow.add(decodeUs)
            renderWindow.add(renderUs)
            if (endToEndUs != null) {
                endToEndWindow.add(endToEndUs)
            }
            updateFpsLocked(renderedAtMs)
            renderedFrames++

            val recorded = FrameStageTimings(
                seq = seq,
                ptsUs = ptsUs,
                captureUs = captureUs,
                receiveQueueUs = receiveQueueUs,
                decodeUs = decodeUs,
                renderUs = renderUs,
                captureToReceiveUs = captureToReceiveUs,
                endToEndUs = endToEndUs,
                clockOffsetUs = offset,
                renderedAtMs = renderedAtMs
            )
            latestFrame = recorded
            publishLocked()
            recorded
        }

        if (timings.seq % LOG_EVERY_N_FRAMES == 0 ||
            (timings.endToEndUs != null && timings.endToEndUs > HIGH_LATENCY_LOG_US)
        ) {
            diagnosticsLog.append(
                renderedAtMs,
                CATEGORY_FRAME,
                "seq=${timings.seq} queueUs=$receiveQueueUs decUs=$decodeUs " +
                    "renUs=$renderUs e2eUs=${timings.endToEndUs ?: -1}"
            )
        }
    }

    fun recordQueueDrop(count: Long = 1L) {
        val nowMs = clock.monotonicMillis()
        val total = lock.withLock {
            droppedFrames += count
            publishLocked()
            droppedFrames
        }
        diagnosticsLog.append(nowMs, CATEGORY_QUEUE_DROP, "droppedTotal=$total")
    }

    fun recordSkippedNonKeyframe(count: Long = 1L) {
        lock.withLock {
            skippedNonKeyframes += count
            publishLocked()
        }
    }

    fun recordKeyframeRequest(reason: String) {
        val nowMs = clock.monotonicMillis()
        val total = lock.withLock {
            keyframeRequestsSent++
            publishLocked()
            keyframeRequestsSent
        }
        diagnosticsLog.append(nowMs, CATEGORY_KEYFRAME_REQ, "total=$total reason=$reason")
    }

    fun recordCodecRecovery(reason: String) {
        val nowMs = clock.monotonicMillis()
        val total = lock.withLock {
            codecRecoveries++
            publishLocked()
            codecRecoveries
        }
        diagnosticsLog.append(nowMs, CATEGORY_DECODER_EVENT, "recovery=$total reason=$reason")
    }

    fun recordDiagnosticsEvent(category: String, message: String) {
        diagnosticsLog.append(clock.monotonicMillis(), category, message)
    }

    fun resetConnectionSync() {
        lock.withLock {
            pingCursor = 0
            pingCount = 0
            bestClockOffsetUs = null
            minRttUs = null
            latestRttUs = null
            endToEndWindow.clear()
            publishLocked()
        }
    }

    fun resetAll() {
        lock.withLock {
            pingCursor = 0
            pingCount = 0
            bestClockOffsetUs = null
            minRttUs = null
            latestRttUs = null
            receiveQueueWindow.clear()
            decodeWindow.clear()
            renderWindow.clear()
            endToEndWindow.clear()
            renderTimeHead = 0
            renderTimeCount = 0
            latestFrame = null
            renderedFrames = 0L
            droppedFrames = 0L
            skippedNonKeyframes = 0L
            keyframeRequestsSent = 0L
            codecRecoveries = 0L
            currentFps = 0f
            publishLocked()
        }
        diagnosticsLog.clear()
    }

    private fun updateFpsLocked(renderedAtMs: Long) {
        renderTimeWindowMs[renderTimeHead] = renderedAtMs
        renderTimeHead = (renderTimeHead + 1) % FPS_WINDOW_SIZE
        if (renderTimeCount < FPS_WINDOW_SIZE) renderTimeCount++
        if (renderTimeCount >= 2) {
            val oldestIdx = if (renderTimeCount < FPS_WINDOW_SIZE) 0 else renderTimeHead
            val spanMs = (renderedAtMs - renderTimeWindowMs[oldestIdx]).coerceAtLeast(1L)
            currentFps = ((renderTimeCount - 1) * MILLIS_PER_SEC_F) / spanMs.toFloat()
        }
    }

    private fun publishLocked() {
        mutableState.value = SessionMetricsSnapshot(
            latestFrame = latestFrame,
            medianReceiveQueueUs = receiveQueueWindow.percentile(MEDIAN_PERCENT) ?: 0L,
            medianDecodeUs = decodeWindow.percentile(MEDIAN_PERCENT) ?: 0L,
            medianRenderUs = renderWindow.percentile(MEDIAN_PERCENT) ?: 0L,
            medianEndToEndUs = endToEndWindow.percentile(MEDIAN_PERCENT),
            p95EndToEndUs = endToEndWindow.percentile(P95_PERCENT),
            clockOffsetUs = bestClockOffsetUs,
            minRttUs = minRttUs,
            latestRttUs = latestRttUs,
            renderedFrames = renderedFrames,
            droppedFrames = droppedFrames,
            skippedNonKeyframes = skippedNonKeyframes,
            keyframeRequestsSent = keyframeRequestsSent,
            codecRecoveries = codecRecoveries,
            fps = currentFps
        )
    }

    private companion object {
        const val PING_WINDOW_SIZE = 16
        const val STAGE_WINDOW_SIZE = 60
        const val FPS_WINDOW_SIZE = 30
        const val MEDIAN_PERCENT = 50
        const val P95_PERCENT = 95
        const val PERCENT_SCALE = 100
        const val MICROS_PER_MILLI = 1_000L
        const val MILLIS_PER_SEC_F = 1_000f
        const val LOG_EVERY_N_FRAMES = 60
        const val HIGH_LATENCY_LOG_US = 150_000L
        const val CATEGORY_CLOCK_SYNC = "CLOCK_SYNC"
        const val CATEGORY_FRAME = "FRAME"
        const val CATEGORY_QUEUE_DROP = "QUEUE_DROP"
        const val CATEGORY_KEYFRAME_REQ = "KEYFRAME_REQ"
        const val CATEGORY_DECODER_EVENT = "DECODER_EVENT"
    }
}
