package com.mastersoft.velocast.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Snapshot of [VideoFrameQueue] policy and recovery counters. */
data class VideoQueueStats(
    val capacity: Int,
    val enqueuedFrames: Long,
    val droppedFrames: Long,
    val skippedNonKeyframes: Long,
    val deliveredFrames: Long,
    val keyframeRequestsSent: Long,
    val waitForKeyframe: Boolean,
    val maxObservedDepth: Int
)

/**
 * Bounded `DROP_OLDEST` video frame queue (FUN-02, PERF-02).
 *
 * - Capacity defaults to [DEFAULT_CAPACITY] (6).
 * - Never suspends the reader thread ([enqueue] uses [Channel.trySend]).
 * - Whenever any frame is evicted by overflow (or an external drop/reset is reported), sets
 *   [waitForKeyframe], triggers [onRequestKeyframe], and skips non-key frames until a frame with
 *   the `KEYFRAME` flag arrives.
 * - Releases every dropped, skipped, or cleared [ReceivedFrame] back to [FrameBufferPool].
 */
class VideoFrameQueue(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clock: SessionClock = SystemSessionClock,
    private val onRequestKeyframe: () -> Unit = {},
    private val onFrameDropped: (droppedTotal: Long) -> Unit = {},
    private val onFrameSkipped: (skippedTotal: Long) -> Unit = {}
) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private class QueuedEntry(val order: Long, val frame: ReceivedFrame)

    private val stateLock = ReentrantLock()
    private val closing = AtomicBoolean(false)
    private val suppressDropCallbacks = AtomicBoolean(false)
    private val nextOrder = AtomicLong(0L)
    private val currentDepth = AtomicInteger(0)
    private val maxDepth = AtomicInteger(0)

    private var waitingForKeyframe = false
    private var latestDropOrder = 0L

    private val enqueuedCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)
    private val skippedCounter = AtomicLong(0L)
    private val deliveredCounter = AtomicLong(0L)
    private val keyframeReqCounter = AtomicLong(0L)

    private val channel = Channel<QueuedEntry>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { evicted -> handleEvictedEntry(evicted) }
    )

    val waitForKeyframe: Boolean
        get() = stateLock.withLock { waitingForKeyframe }

    val approximateDepth: Int
        get() = currentDepth.get().coerceAtLeast(0)

    fun stats(): VideoQueueStats = VideoQueueStats(
        capacity = capacity,
        enqueuedFrames = enqueuedCounter.get(),
        droppedFrames = droppedCounter.get(),
        skippedNonKeyframes = skippedCounter.get(),
        deliveredFrames = deliveredCounter.get(),
        keyframeRequestsSent = keyframeReqCounter.get(),
        waitForKeyframe = waitForKeyframe,
        maxObservedDepth = maxDepth.get()
    )

    /**
     * Enqueues [frame] without blocking or suspending. Returns `true` if accepted into the queue.
     */
    fun enqueue(frame: ReceivedFrame): Boolean {
        var accepted = false
        if (closing.get()) {
            frame.release()
        } else {
            if (frame.receivedAtUs == 0L) {
                frame.receivedAtUs = clock.monotonicMicros()
            }
            val order = nextOrder.incrementAndGet()
            enqueuedCounter.incrementAndGet()
            val depthAfterAdd = currentDepth.incrementAndGet()
            recordMaxDepth(depthAfterAdd.coerceAtMost(capacity))

            val result = channel.trySend(QueuedEntry(order, frame))
            if (result.isSuccess) {
                accepted = true
            } else {
                currentDepth.decrementAndGet()
                frame.release()
            }
        }
        return accepted
    }

    /**
     * Marks the queue as waiting for a keyframe (e.g., server-side sequence gap,
     * `DROPPED_FRAMES` status, or decoder `CodecException` reset) and optionally emits
     * `KEYFRAME_REQ`.
     */
    fun notifyExternalDropOrReset(sendKeyframeRequest: Boolean = true) {
        if (closing.get()) return
        stateLock.withLock {
            waitingForKeyframe = true
            latestDropOrder = nextOrder.get()
        }
        if (sendKeyframeRequest) {
            keyframeReqCounter.incrementAndGet()
            onRequestKeyframe()
        }
    }

    /**
     * Suspends until the next decodable [ReceivedFrame] is available, or returns `null` once the
     * queue is closed. Non-key frames received while [waitForKeyframe] is active are released and
     * skipped immediately.
     */
    suspend fun receiveNext(): ReceivedFrame? {
        while (true) {
            val entry = channel.receiveCatching().getOrNull() ?: return null
            currentDepth.decrementAndGet()
            val shouldDeliver = stateLock.withLock {
                if (!waitingForKeyframe) {
                    true
                } else if (entry.frame.isKeyframe) {
                    if (entry.order > latestDropOrder) {
                        waitingForKeyframe = false
                    }
                    true
                } else {
                    false
                }
            }
            if (shouldDeliver) {
                deliveredCounter.incrementAndGet()
                return entry.frame
            }
            entry.frame.release()
            val skipped = skippedCounter.incrementAndGet()
            onFrameSkipped(skipped)
        }
    }

    /** Clears all buffered frames, releasing their pool buffers without sending `KEYFRAME_REQ`. */
    fun clear(requestKeyframeAfter: Boolean = false) {
        suppressDropCallbacks.set(true)
        try {
            while (true) {
                val entry = channel.tryReceive().getOrNull() ?: break
                currentDepth.decrementAndGet()
                entry.frame.release()
            }
        } finally {
            suppressDropCallbacks.set(false)
        }
        if (requestKeyframeAfter && !closing.get()) {
            notifyExternalDropOrReset(sendKeyframeRequest = true)
        }
    }

    /** Closes the queue and releases all buffered frames. */
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        suppressDropCallbacks.set(true)
        try {
            channel.close()
            while (true) {
                val entry = channel.tryReceive().getOrNull() ?: break
                currentDepth.decrementAndGet()
                entry.frame.release()
            }
        } finally {
            suppressDropCallbacks.set(false)
        }
    }

    private fun handleEvictedEntry(evicted: QueuedEntry) {
        currentDepth.decrementAndGet()
        evicted.frame.release()
        if (closing.get() || suppressDropCallbacks.get()) {
            return
        }
        stateLock.withLock {
            waitingForKeyframe = true
            if (evicted.order > latestDropOrder) {
                latestDropOrder = evicted.order
            }
        }
        val dropped = droppedCounter.incrementAndGet()
        keyframeReqCounter.incrementAndGet()
        onFrameDropped(dropped)
        onRequestKeyframe()
    }

    private fun recordMaxDepth(candidate: Int) {
        while (true) {
            val prev = maxDepth.get()
            if (candidate <= prev) return
            if (maxDepth.compareAndSet(prev, candidate)) return
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 6
    }
}
