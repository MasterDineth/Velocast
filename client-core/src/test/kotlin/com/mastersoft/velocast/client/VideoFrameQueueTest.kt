package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.MessageType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoFrameQueueTest {

    @Test
    fun `capacity 6 DROP_OLDEST evicts oldest without suspending and skips until KEYFRAME`() =
        runTest {
            val pool = FrameBufferPool(capacity = 32, initialBufferBytes = 64)
            val keyframeRequests = AtomicInteger(0)
            val queue = VideoFrameQueue(
                capacity = 6,
                onRequestKeyframe = { keyframeRequests.incrementAndGet() }
            )

            // Enqueue 10 frames while consumer is stalled: capacity 6 keeps newest 6 (seq 5..10),
            // dropping 4 oldest (seq 1..4).
            for (seq in 1..10) {
                val frame = pool.acquireVideoFrame(16)
                val flags = if (seq == 1 || seq == 8) FrameFlags.KEYFRAME else 0
                frame.updateHeader(
                    MessageType.VIDEO,
                    flags,
                    seq,
                    seq * 1000L,
                    seq * 1000L,
                    seq * 1000L
                )
                assertTrue(queue.enqueue(frame))
            }

            assertTrue(queue.waitForKeyframe)
            assertEquals(4L, queue.stats().droppedFrames)
            assertEquals(4, keyframeRequests.get())
            assertEquals(6, queue.stats().maxObservedDepth)
            // Only the 6 buffered frames should remain leased in the pool; dropped 4 were released.
            assertEquals(6, pool.inUseCount)

            // Buffered frames are seq 5, 6, 7 (delta), 8 (KEYFRAME), 9, 10 (delta).
            // Seq 5, 6, 7 must be skipped and released until seq 8 (KEYFRAME) arrives.
            val firstDelivered = queue.receiveNext()
            assertNotNull(firstDelivered)
            assertEquals(8, firstDelivered!!.seq)
            assertTrue(firstDelivered.isKeyframe)
            assertFalse(queue.waitForKeyframe)
            firstDelivered.release()

            assertEquals(3L, queue.stats().skippedNonKeyframes)

            val seq9 = queue.receiveNext()!!
            assertEquals(9, seq9.seq)
            seq9.release()

            val seq10 = queue.receiveNext()!!
            assertEquals(10, seq10.seq)
            seq10.release()

            assertEquals(0, pool.inUseCount)
            queue.close()
        }

    @Test
    fun `stall followed by post-drop deltas skips all deltas until recovery KEYFRAME arrives`() =
        runTest {
            val pool = FrameBufferPool(capacity = 16, initialBufferBytes = 64)
            val keyframeRequests = AtomicInteger(0)
            val queue = VideoFrameQueue(
                capacity = 6,
                onRequestKeyframe = { keyframeRequests.incrementAndGet() }
            )

            // Enqueue 8 delta frames (seq 1..8) -> 2 dropped, buffered are seq 3..8 (all non-key).
            for (seq in 1..8) {
                val f = pool.acquireVideoFrame(8)
                f.updateHeader(
                    MessageType.VIDEO,
                    0,
                    seq,
                    seq * 16_667L,
                    seq * 16_667L,
                    seq * 16_667L
                )
                queue.enqueue(f)
            }
            assertTrue(queue.waitForKeyframe)
            assertEquals(2, keyframeRequests.get())

            // Enqueue recovery keyframe (seq 9) and trailing delta (seq 10).
            val kf = pool.acquireVideoFrame(8).apply {
                updateHeader(
                    MessageType.VIDEO,
                    FrameFlags.KEYFRAME,
                    9,
                    9 * 16_667L,
                    9 * 16_667L,
                    9 * 16_667L
                )
            }
            val p10 = pool.acquireVideoFrame(8).apply {
                updateHeader(MessageType.VIDEO, 0, 10, 10 * 16_667L, 10 * 16_667L, 10 * 16_667L)
            }
            // Note: seq 9 + seq 10 into already full queue of 6 drops 2 more deltas (seq 3, 4).
            queue.enqueue(kf)
            queue.enqueue(p10)

            val delivered = queue.receiveNext()!!
            assertEquals(9, delivered.seq)
            assertTrue(delivered.isKeyframe)
            assertFalse(queue.waitForKeyframe)
            delivered.release()

            val deliveredDelta = queue.receiveNext()!!
            assertEquals(10, deliveredDelta.seq)
            deliveredDelta.release()

            assertEquals(4L, queue.stats().droppedFrames)
            assertEquals(4L, queue.stats().skippedNonKeyframes)
            assertEquals(0, pool.inUseCount)
            queue.close()
        }

    @Test
    fun `notifyExternalDropOrReset sets waitForKeyframe and close releases buffered frames`() =
        runTest {
            val pool = FrameBufferPool(capacity = 8, initialBufferBytes = 64)
            val keyframeRequests = AtomicInteger(0)
            val queue = VideoFrameQueue(
                capacity = 6,
                onRequestKeyframe = { keyframeRequests.incrementAndGet() }
            )

            queue.notifyExternalDropOrReset(sendKeyframeRequest = true)
            assertTrue(queue.waitForKeyframe)
            assertEquals(1, keyframeRequests.get())

            repeat(3) { idx ->
                val f = pool.acquireVideoFrame(16)
                f.updateHeader(MessageType.VIDEO, 0, idx + 1, 100L, 100L, 100L)
                queue.enqueue(f)
            }
            assertEquals(3, pool.inUseCount)

            queue.close()
            assertEquals(0, pool.inUseCount)
            assertNull(queue.receiveNext())
            // Closing must not send spurious KEYFRAME_REQ.
            assertEquals(1, keyframeRequests.get())
        }
}
