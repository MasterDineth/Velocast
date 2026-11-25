package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.ProtocolV2
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reusable buffer slot holding one encoded video access unit plus space at `[0..FRAME_HEADER_SIZE)`
 * for in-place wire framing by `FrameWriter.writeInPlace`.
 */
class VideoFrameSlot(initialPayloadCapacity: Int = FrameQueue.DEFAULT_INITIAL_CAPACITY) {
    var buffer: ByteArray = ByteArray(ProtocolV2.FRAME_HEADER_SIZE + initialPayloadCapacity)
        private set

    var payloadLength: Int = 0
    var flags: Int = 0
    var ptsUs: Long = 0L
    var captureUs: Long = 0L

    val isKeyframe: Boolean
        get() = (flags and FrameFlags.KEYFRAME) != 0

    fun ensurePayloadCapacity(payloadBytes: Int) {
        require(payloadBytes >= 0) { "payloadBytes must not be negative: $payloadBytes" }
        val needed = ProtocolV2.FRAME_HEADER_SIZE + payloadBytes
        if (buffer.size < needed) {
            buffer = ByteArray(needed)
        }
    }

    fun reset() {
        payloadLength = 0
        flags = 0
        ptsUs = 0L
        captureUs = 0L
    }
}

/** Result of polling [FrameQueue], including how many frames were dropped before this one. */
data class DequeuedVideoFrame(val slot: VideoFrameSlot, val droppedBeforeFrame: Int)

/**
 * Bounded non-blocking latest-wins video queue (PERF-01).
 *
 * When full (`size >= capacity`), drops the oldest non-keyframe first so any pending IDR survives;
 * if every queued frame is already a keyframe, drops the oldest keyframe instead. Never blocks the
 * producer thread on socket I/O and invokes [onFrameDropped] outside the queue lock whenever a
 * frame is shed so the encoder can immediately emit a recovery IDR.
 */
class FrameQueue(
    val capacity: Int = DEFAULT_CAPACITY,
    private val initialPayloadCapacity: Int = DEFAULT_INITIAL_CAPACITY,
    private val onFrameDropped: (() -> Unit)? = null
) {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<VideoFrameSlot>(capacity)
    private val freeSlots = ArrayDeque<VideoFrameSlot>(capacity + EXTRA_FREE_SLOTS)

    private var droppedSinceLastPoll = 0
    private var totalDropped = 0L
    private var maxObserved = 0

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
        require(initialPayloadCapacity >= 0) {
            "initialPayloadCapacity must not be negative: $initialPayloadCapacity"
        }
    }

    val size: Int
        get() = lock.withLock { queue.size }

    val maxObservedDepth: Int
        get() = lock.withLock { maxObserved }

    val totalDroppedFrames: Long
        get() = lock.withLock { totalDropped }

    fun acquireSlot(payloadBytes: Int): VideoFrameSlot {
        val slot = lock.withLock {
            if (freeSlots.isNotEmpty()) {
                freeSlots.removeLast()
            } else {
                null
            }
        } ?: VideoFrameSlot(maxOf(initialPayloadCapacity, payloadBytes))
        slot.reset()
        slot.ensurePayloadCapacity(payloadBytes)
        return slot
    }

    fun releaseSlot(slot: VideoFrameSlot) {
        lock.withLock {
            if (freeSlots.size < capacity + EXTRA_FREE_SLOTS) {
                slot.reset()
                freeSlots.addLast(slot)
            }
        }
    }

    /**
     * Enqueues [slot] without blocking on I/O. Returns `true` when an older frame was dropped to
     * keep depth bounded at [capacity].
     */
    fun enqueue(slot: VideoFrameSlot): Boolean {
        var dropped = false
        lock.withLock {
            if (queue.size >= capacity) {
                val evicted = evictOneLocked()
                recycleLocked(evicted)
                droppedSinceLastPoll++
                totalDropped++
                dropped = true
            }
            queue.addLast(slot)
            if (queue.size > maxObserved) {
                maxObserved = queue.size
            }
        }
        if (dropped) {
            onFrameDropped?.invoke()
        }
        return dropped
    }

    fun poll(): DequeuedVideoFrame? = lock.withLock {
        if (queue.isEmpty()) {
            null
        } else {
            val slot = queue.removeFirst()
            val dropped = droppedSinceLastPoll
            droppedSinceLastPoll = 0
            DequeuedVideoFrame(slot = slot, droppedBeforeFrame = dropped)
        }
    }

    fun clear(): Int = lock.withLock {
        val count = queue.size
        while (queue.isNotEmpty()) {
            recycleLocked(queue.removeFirst())
        }
        droppedSinceLastPoll += count
        totalDropped += count
        count
    }

    fun resetMetrics() {
        lock.withLock {
            droppedSinceLastPoll = 0
            totalDropped = 0L
            maxObserved = queue.size
        }
    }

    private fun evictOneLocked(): VideoFrameSlot {
        val iter = queue.iterator()
        while (iter.hasNext()) {
            val candidate = iter.next()
            if (!candidate.isKeyframe) {
                iter.remove()
                return candidate
            }
        }
        return queue.removeFirst()
    }

    private fun recycleLocked(slot: VideoFrameSlot) {
        if (freeSlots.size < capacity + EXTRA_FREE_SLOTS) {
            slot.reset()
            freeSlots.addLast(slot)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 3
        const val DEFAULT_INITIAL_CAPACITY = 256 * 1024
        private const val EXTRA_FREE_SLOTS = 2
    }
}
