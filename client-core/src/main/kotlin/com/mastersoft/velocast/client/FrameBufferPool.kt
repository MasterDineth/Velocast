package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.ProtocolV2
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed-ring pool of preallocated byte arrays and [ReceivedFrame] holders for zero-allocation
 * inbound `VIDEO` frame reading (PERF-03).
 *
 * When all ring slots are concurrently leased, [acquireVideoFrame] falls back to an unpooled
 * allocation so the network reader never stalls.
 */
class FrameBufferPool(
    val capacity: Int = DEFAULT_CAPACITY,
    val initialBufferBytes: Int = DEFAULT_INITIAL_BUFFER_BYTES
) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
        require(initialBufferBytes in 1..ProtocolV2.MAX_PAYLOAD) {
            "initialBufferBytes must be in 1..${ProtocolV2.MAX_PAYLOAD}: $initialBufferBytes"
        }
    }

    internal class Slot(initialBytes: Int, val pool: FrameBufferPool) {
        var buffer: ByteArray = ByteArray(initialBytes)
        val inUse = AtomicBoolean(false)
        val epoch = AtomicInteger(0)
        val frame = ReceivedFrame(
            type = MessageType.VIDEO,
            flags = 0,
            seq = 0,
            ptsUs = 0L,
            captureUs = 0L,
            payload = buffer,
            payloadLength = 0,
            receivedAtUs = 0L
        )
    }

    private val slots = Array(capacity) { Slot(initialBufferBytes, this) }
    private val cursor = AtomicInteger(0)
    private val leasedCount = AtomicInteger(0)
    private val pooledAcquires = AtomicLong(0L)
    private val fallbackAllocationsCount = AtomicLong(0L)

    val inUseCount: Int
        get() = leasedCount.get()

    val pooledAcquireCount: Long
        get() = pooledAcquires.get()

    val fallbackAllocations: Long
        get() = fallbackAllocationsCount.get()

    /**
     * Acquires a [ReceivedFrame] backed by a buffer of at least [requiredBytes]. The caller must
     * call [ReceivedFrame.release] once downstream processing completes.
     */
    fun acquireVideoFrame(requiredBytes: Int): ReceivedFrame {
        require(requiredBytes in 0..ProtocolV2.MAX_PAYLOAD) {
            "requiredBytes must be in 0..${ProtocolV2.MAX_PAYLOAD}: $requiredBytes"
        }
        val start = (cursor.getAndIncrement() and Int.MAX_VALUE) % capacity
        for (offset in 0 until capacity) {
            val slot = slots[(start + offset) % capacity]
            if (slot.inUse.compareAndSet(false, true)) {
                if (slot.buffer.size < requiredBytes) {
                    slot.buffer = ByteArray(requiredBytes)
                }
                val epoch = slot.epoch.incrementAndGet()
                leasedCount.incrementAndGet()
                pooledAcquires.incrementAndGet()
                slot.frame.bindFromPool(
                    slot = slot,
                    epoch = epoch,
                    buffer = slot.buffer,
                    payloadLength = requiredBytes
                )
                return slot.frame
            }
        }
        fallbackAllocationsCount.incrementAndGet()
        return ReceivedFrame(
            type = MessageType.VIDEO,
            flags = 0,
            seq = 0,
            ptsUs = 0L,
            captureUs = 0L,
            payload = ByteArray(requiredBytes),
            payloadLength = requiredBytes,
            receivedAtUs = 0L
        )
    }

    internal fun releaseSlot(slot: Slot, expectedEpoch: Int) {
        if (slot.epoch.get() == expectedEpoch && slot.inUse.compareAndSet(true, false)) {
            leasedCount.decrementAndGet()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 24
        const val DEFAULT_INITIAL_BUFFER_BYTES = 256 * 1024
    }
}
