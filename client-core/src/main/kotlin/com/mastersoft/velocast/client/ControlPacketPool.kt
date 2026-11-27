package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ControlPacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal sealed interface OutboundWriteItem

/**
 * Reusable [ControlPacket] slot managed by [ControlPacketPool].
 */
class PooledControlSlot internal constructor(
    private val pool: ControlPacketPool?,
    internal val inUse: AtomicBoolean = AtomicBoolean(false),
    internal val epoch: AtomicInteger = AtomicInteger(0)
) : OutboundWriteItem {
    val packet: ControlPacket = ControlPacket()

    @Volatile
    internal var boundEpoch: Int = 0

    fun copyFrom(source: ControlPacket) {
        packet.type = source.type
        packet.pointer = source.pointer
        packet.seq = source.seq
        packet.param = source.param
        packet.x = source.x
        packet.y = source.y
        packet.payloadLength = source.payloadLength
    }

    fun release() {
        pool?.releaseSlot(this, boundEpoch)
    }
}

/**
 * Fixed-ring pool of preallocated [ControlPacket] slots for outbound high-rate touch and ping
 * packets so control writes allocate nothing on the hot path (PERF-03).
 */
class ControlPacketPool(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val slots = Array(capacity) { PooledControlSlot(this) }
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

    fun acquire(): PooledControlSlot {
        val start = (cursor.getAndIncrement() and Int.MAX_VALUE) % capacity
        for (offset in 0 until capacity) {
            val slot = slots[(start + offset) % capacity]
            if (slot.inUse.compareAndSet(false, true)) {
                val nextEpoch = slot.epoch.incrementAndGet()
                slot.boundEpoch = nextEpoch
                leasedCount.incrementAndGet()
                pooledAcquires.incrementAndGet()
                return slot
            }
        }
        fallbackAllocationsCount.incrementAndGet()
        return PooledControlSlot(pool = null)
    }

    internal fun releaseSlot(slot: PooledControlSlot, expectedEpoch: Int) {
        if (slot.epoch.get() == expectedEpoch && slot.inUse.compareAndSet(true, false)) {
            leasedCount.decrementAndGet()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}
