package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.FrameFlags
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueTest {
    private fun makeSlot(
        queue: FrameQueue,
        marker: Int,
        keyframe: Boolean,
        ptsUs: Long
    ): VideoFrameSlot {
        val slot = queue.acquireSlot(4)
        slot.buffer[com.mastersoft.velocast.protocol.ProtocolV2.FRAME_HEADER_SIZE] = marker.toByte()
        slot.payloadLength = 1
        slot.flags = if (keyframe) FrameFlags.KEYFRAME else 0
        slot.ptsUs = ptsUs
        slot.captureUs = ptsUs
        return slot
    }

    private fun markerOf(slot: VideoFrameSlot): Int =
        slot.buffer[com.mastersoft.velocast.protocol.ProtocolV2.FRAME_HEADER_SIZE].toInt() and 0xFF

    @Test
    fun dropsOldestNonKeyframeFirstWhilePreservingKeyframeAndInvokingCallback() {
        val idrRequests = AtomicInteger(0)
        val queue = FrameQueue(capacity = 3) { idrRequests.incrementAndGet() }

        assertFalse(queue.enqueue(makeSlot(queue, marker = 1, keyframe = true, ptsUs = 100L)))
        assertFalse(queue.enqueue(makeSlot(queue, marker = 2, keyframe = false, ptsUs = 200L)))
        assertFalse(queue.enqueue(makeSlot(queue, marker = 3, keyframe = false, ptsUs = 300L)))
        assertEquals(3, queue.size)
        assertEquals(0, idrRequests.get())

        // Enqueue 4th frame: must drop P-frame #2 (oldest non-keyframe) and keep IDR #1.
        assertTrue(queue.enqueue(makeSlot(queue, marker = 4, keyframe = false, ptsUs = 400L)))
        assertEquals(3, queue.size)
        assertEquals(3, queue.maxObservedDepth)
        assertEquals(1L, queue.totalDroppedFrames)
        assertEquals(1, idrRequests.get())

        val first = queue.poll()
        assertNotNull(first)
        assertEquals(1, markerOf(first!!.slot))
        assertTrue(first.slot.isKeyframe)
        assertEquals(1, first.droppedBeforeFrame)

        val second = queue.poll()
        assertEquals(3, markerOf(second!!.slot))
        assertEquals(0, second.droppedBeforeFrame)

        val third = queue.poll()
        assertEquals(4, markerOf(third!!.slot))
        assertEquals(0, third.droppedBeforeFrame)
        assertNull(queue.poll())
    }

    @Test
    fun dropsOldestNonKeyframeWhenAllEntriesAreDeltaFrames() {
        val idrRequests = AtomicInteger(0)
        val queue = FrameQueue(capacity = 3) { idrRequests.incrementAndGet() }

        repeat(6) { idx ->
            queue.enqueue(
                makeSlot(
                    queue,
                    marker = idx + 1,
                    keyframe = false,
                    ptsUs =
                        (idx + 1) * 16L
                )
            )
        }

        assertEquals(3, queue.size)
        assertEquals(3, queue.maxObservedDepth)
        assertEquals(3L, queue.totalDroppedFrames)
        assertEquals(3, idrRequests.get())

        val d1 = queue.poll()!!
        assertEquals(4, markerOf(d1.slot))
        assertEquals(3, d1.droppedBeforeFrame)
        assertEquals(5, markerOf(queue.poll()!!.slot))
        assertEquals(6, markerOf(queue.poll()!!.slot))
    }

    @Test
    fun fallsBackToDroppingOldestKeyframeWhenAllSlotsHoldKeyframes() {
        val idrRequests = AtomicInteger(0)
        val queue = FrameQueue(capacity = 3) { idrRequests.incrementAndGet() }

        queue.enqueue(makeSlot(queue, marker = 10, keyframe = true, ptsUs = 10L))
        queue.enqueue(makeSlot(queue, marker = 20, keyframe = true, ptsUs = 20L))
        queue.enqueue(makeSlot(queue, marker = 30, keyframe = true, ptsUs = 30L))
        assertTrue(queue.enqueue(makeSlot(queue, marker = 40, keyframe = false, ptsUs = 40L)))

        assertEquals(3, queue.size)
        assertEquals(3, queue.maxObservedDepth)
        assertEquals(20, markerOf(queue.poll()!!.slot))
        assertEquals(30, markerOf(queue.poll()!!.slot))
        assertEquals(40, markerOf(queue.poll()!!.slot))
        assertEquals(1, idrRequests.get())
    }

    @Test
    fun clearRecyclesAllQueuedSlotsAndRecordsDropsAndReusesPool() {
        val queue = FrameQueue(capacity = 3, initialPayloadCapacity = 1024)
        val slotA = queue.acquireSlot(512)
        slotA.payloadLength = 4
        queue.enqueue(slotA)

        assertEquals(1, queue.clear())
        assertEquals(0, queue.size)
        assertEquals(1L, queue.totalDroppedFrames)

        // Acquiring again reuses the recycled slot instance without allocating a new slot.
        val reused = queue.acquireSlot(512)
        assertSame(slotA, reused)
        queue.releaseSlot(reused)
    }
}
