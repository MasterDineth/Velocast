package com.mastersoft.velocast.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMetricsTest {

    @Test
    fun `computes min-RTT clock offset, per-frame stage timings, and StateFlow snapshots`() {
        var nowUs = 1_000_000L
        val clock = object : SessionClock {
            override fun monotonicMillis(): Long = nowUs / 1_000L
            override fun monotonicMicros(): Long = nowUs
        }
        val metrics = SessionMetrics(clock = clock, logCapacity = 8)

        // Sample 1: RTT = 20_000 us, offset = serverUs(5_010_000) - mid(1_010_000) = 4_000_000 us.
        metrics.recordPong(t0Us = 1_000_000L, t1Us = 1_020_000L, serverUs = 5_010_000L)
        assertEquals(20_000L, metrics.state.value.minRttUs)
        assertEquals(4_000_000L, metrics.state.value.clockOffsetUs)

        // Sample 2: lower RTT = 4_000 us, offset = serverUs(5_503_000) - mid(1_502_000) = 4_001_000 us.
        metrics.recordPong(t0Us = 1_500_000L, t1Us = 1_504_000L, serverUs = 5_503_000L)
        assertEquals(4_000L, metrics.state.value.minRttUs)
        assertEquals(4_001_000L, metrics.state.value.clockOffsetUs)

        // Sample 3: higher RTT = 40_000 us must NOT overwrite min-RTT clock offset.
        metrics.recordPong(t0Us = 2_000_000L, t1Us = 2_040_000L, serverUs = 6_900_000L)
        assertEquals(4_000L, metrics.state.value.minRttUs)
        assertEquals(40_000L, metrics.state.value.latestRttUs)
        assertEquals(4_001_000L, metrics.state.value.clockOffsetUs)

        // Record a frame: captureUs = 6_001_000 => aligned client capture = 6_001_000 - 4_001_000 = 2_000_000 us.
        metrics.recordFrameRendered(
            seq = 60,
            ptsUs = 16_667L,
            captureUs = 6_001_000L,
            receivedAtUs = 2_010_000L,
            dequeuedForDecodeUs = 2_012_000L,
            decodedAtUs = 2_017_000L,
            renderedAtUs = 2_019_000L
        )

        val latest = metrics.state.value.latestFrame
        assertNotNull(latest)
        assertEquals(2_000L, latest!!.receiveQueueUs)
        assertEquals(5_000L, latest.decodeUs)
        assertEquals(2_000L, latest.renderUs)
        assertEquals(10_000L, latest.captureToReceiveUs)
        assertEquals(19_000L, latest.endToEndUs)
        assertEquals(1L, metrics.state.value.renderedFrames)
        assertEquals(2_000L, metrics.state.value.medianReceiveQueueUs)
        assertEquals(5_000L, metrics.state.value.medianDecodeUs)
        assertEquals(2_000L, metrics.state.value.medianRenderUs)
        assertEquals(19_000L, metrics.state.value.medianEndToEndUs)
    }

    @Test
    fun `diagnostics ring buffer wraps at capacity preserving chronological order`() {
        val ring = DiagnosticsRingBuffer(capacity = 3)
        repeat(5) { idx ->
            ring.append(timestampMs = idx * 10L, category = "CAT", message = "msg-$idx")
        }
        val entries = ring.snapshot()
        assertEquals(listOf("msg-2", "msg-3", "msg-4"), entries.map { it.message })
        assertTrue(entries.all { it.category == "CAT" })
    }
}
