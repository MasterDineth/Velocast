package com.mastersoft.velocast.client

import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.ProtocolV2
import com.mastersoft.velocast.protocol.TouchAction
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reliable, ordered touch delivery pipeline with per-pointer `MOVE` coalescing (FUN-05, PERF-03).
 *
 * - `DOWN`, `UP`, and `CANCEL` are never dropped or reordered relative to each other.
 * - While a `MOVE` for `pointerId` is queued and unsent, subsequent `MOVE` updates overwrite its
 *   coordinates in place (latest wins, zero allocation, preserving queue order).
 * - [flushCancelOnPauseOrDisconnect] emits `CANCEL` and flushes queued touch transitions before
 *   `PAUSE` or `BYE` leaves the client so the server never holds a stuck pointer.
 */
class TouchPipeline(
    transport: StreamTransport,
    scope: CoroutineScope,
    val viewportMapper: ViewportMapper = ViewportMapper()
) {
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val state = TouchQueueState { wakeSignal.trySend(Unit) }
    private val sender = TouchPacketSender(transport, state, wakeSignal)
    private val coordScratch = FloatArray(2)
    private var senderJob: Job? = null

    init {
        senderJob = scope.launch { sender.runLoop() }
    }

    val activePointerCount: Int
        get() = state.activePointerCount

    val queuedCount: Int
        get() = state.queuedCount

    fun isPointerDown(pointerId: Int): Boolean = state.isPointerDown(pointerId)

    fun onRawDown(pointerId: Int, rawX: Float, rawY: Float): Boolean =
        if (viewportMapper.mapTouchDown(rawX, rawY, coordScratch)) {
            state.enqueueDown(pointerId, coordScratch[0], coordScratch[1])
        } else {
            false
        }

    fun onRawMove(pointerId: Int, rawX: Float, rawY: Float): Boolean =
        if (viewportMapper.mapActivePointer(rawX, rawY, coordScratch)) {
            state.enqueueMove(pointerId, coordScratch[0], coordScratch[1])
        } else {
            false
        }

    fun onRawUp(pointerId: Int, rawX: Float, rawY: Float): Boolean =
        if (viewportMapper.mapActivePointer(rawX, rawY, coordScratch)) {
            state.enqueueUp(pointerId, coordScratch[0], coordScratch[1])
        } else {
            false
        }

    fun onDown(pointerId: Int, normX: Float, normY: Float): Boolean =
        state.enqueueDown(pointerId, normX, normY)

    fun onMove(pointerId: Int, normX: Float, normY: Float): Boolean =
        state.enqueueMove(pointerId, normX, normY)

    fun onUp(pointerId: Int, normX: Float, normY: Float): Boolean =
        state.enqueueUp(pointerId, normX, normY)

    fun cancelAllPointers(): Boolean = state.cancelAllPointers()

    /**
     * Cancels any active pointers and flushes all queued touch transitions to transport before
     * sending `PAUSE` or `BYE`. Emits nothing if no pointer is active or queued.
     */
    suspend fun flushCancelOnPauseOrDisconnect() {
        if (!state.prepareCancelFlush()) return
        sender.flushNow()
    }

    /** Clears local pointer state after an unexpected transport drop so nothing leaks on reconnect. */
    fun resetOnTransportLost() {
        state.reset()
    }

    fun close() {
        state.reset()
        senderJob?.cancel()
        senderJob = null
        wakeSignal.close()
    }
}

private class TouchPacketSender(
    private val transport: StreamTransport,
    private val state: TouchQueueState,
    private val wakeSignal: Channel<Unit>
) {
    private val sendMutex = Mutex()
    private val senderPacket = ControlPacket()
    private val flushPacket = ControlPacket()

    suspend fun runLoop() {
        while (true) {
            val received = wakeSignal.receiveCatching()
            if (received.isClosed) break
            sendMutex.withLock { drainQueueLocked(senderPacket) }
        }
    }

    suspend fun flushNow() {
        sendMutex.withLock { drainQueueLocked(flushPacket) }
    }

    private suspend fun drainQueueLocked(reusablePacket: ControlPacket) {
        var keepDraining = state.pollInto(reusablePacket)
        while (keepDraining) {
            keepDraining = try {
                transport.writeControlPacket(reusablePacket)
                state.pollInto(reusablePacket)
            } catch (_: IOException) {
                state.reset()
                false
            }
        }
    }
}

private class TouchSlot(
    var pointer: Int = 0,
    var action: TouchAction = TouchAction.DOWN,
    var x: Float = 0f,
    var y: Float = 0f
)

private object TouchSlotPool {
    private const val INITIAL_SLOT_POOL_SIZE = 64
    private const val MAX_SLOT_POOL_SIZE = 128

    fun createInitialPool(): ArrayDeque<TouchSlot> = ArrayDeque<TouchSlot>(INITIAL_SLOT_POOL_SIZE)
        .apply { repeat(INITIAL_SLOT_POOL_SIZE) { addLast(TouchSlot()) } }

    fun acquire(pool: ArrayDeque<TouchSlot>): TouchSlot =
        if (pool.isNotEmpty()) pool.removeLast() else TouchSlot()

    fun recycle(pool: ArrayDeque<TouchSlot>, slot: TouchSlot) {
        if (pool.size < MAX_SLOT_POOL_SIZE) {
            pool.addLast(slot)
        }
    }

    fun prunePendingMoves(orderedQueue: ArrayDeque<TouchSlot>, pool: ArrayDeque<TouchSlot>) {
        if (orderedQueue.isEmpty()) return
        val iter = orderedQueue.iterator()
        while (iter.hasNext()) {
            val candidate = iter.next()
            if (candidate.action == TouchAction.MOVE) {
                iter.remove()
                recycle(pool, candidate)
            }
        }
    }

    fun isValidArgs(pointerId: Int, normX: Float, normY: Float): Boolean =
        pointerId in 0..ProtocolV2.MAX_POINTER_ID &&
            normX.isFinite() && normX in 0f..1f &&
            normY.isFinite() && normY in 0f..1f

    fun normalizeZero(value: Float): Float = if (value == 0f) 0f else value
}

private class TouchQueueState(private val onWake: () -> Unit) {
    private val lock = Any()
    private val slotPool = TouchSlotPool.createInitialPool()
    private val orderedQueue = ArrayDeque<TouchSlot>()
    private val pendingMoveByPointer = arrayOfNulls<TouchSlot>(POINTER_SLOTS)
    private var activePointersMask: Int = 0

    val activePointerCount: Int
        get() = synchronized(lock) { Integer.bitCount(activePointersMask) }

    val queuedCount: Int
        get() = synchronized(lock) { orderedQueue.size }

    fun isPointerDown(pointerId: Int): Boolean = synchronized(lock) {
        pointerId in 0..ProtocolV2.MAX_POINTER_ID &&
            (activePointersMask and (1 shl pointerId)) != 0
    }

    fun enqueueDown(pointerId: Int, normX: Float, normY: Float): Boolean {
        val accepted = synchronized(lock) {
            val canPress = TouchSlotPool.isValidArgs(pointerId, normX, normY) &&
                (activePointersMask and (1 shl pointerId)) == 0
            if (canPress) {
                activePointersMask = activePointersMask or (1 shl pointerId)
                pendingMoveByPointer[pointerId] = null
                enqueueSlotLocked(pointerId, TouchAction.DOWN, normX, normY)
            }
            canPress
        }
        if (accepted) onWake()
        return accepted
    }

    fun enqueueMove(pointerId: Int, normX: Float, normY: Float): Boolean {
        var shouldWake = false
        val accepted = synchronized(lock) {
            val canMove = TouchSlotPool.isValidArgs(pointerId, normX, normY) &&
                (activePointersMask and (1 shl pointerId)) != 0
            if (canMove) {
                val pendingMove = pendingMoveByPointer[pointerId]
                if (pendingMove != null) {
                    pendingMove.x = TouchSlotPool.normalizeZero(normX)
                    pendingMove.y = TouchSlotPool.normalizeZero(normY)
                } else {
                    val slot = enqueueSlotLocked(pointerId, TouchAction.MOVE, normX, normY)
                    pendingMoveByPointer[pointerId] = slot
                    shouldWake = true
                }
            }
            canMove
        }
        if (shouldWake) onWake()
        return accepted
    }

    fun enqueueUp(pointerId: Int, normX: Float, normY: Float): Boolean {
        val accepted = synchronized(lock) {
            val bit = 1 shl pointerId
            val canRelease = TouchSlotPool.isValidArgs(pointerId, normX, normY) &&
                (activePointersMask and bit) != 0
            if (canRelease) {
                activePointersMask = activePointersMask and bit.inv()
                pendingMoveByPointer[pointerId] = null
                enqueueSlotLocked(pointerId, TouchAction.UP, normX, normY)
            }
            canRelease
        }
        if (accepted) onWake()
        return accepted
    }

    fun cancelAllPointers(): Boolean {
        val enqueued = synchronized(lock) {
            val hasActive = activePointersMask != 0 || orderedQueue.isNotEmpty()
            if (hasActive) {
                enqueueCancelLocked()
            }
            hasActive
        }
        if (enqueued) onWake()
        return enqueued
    }

    fun prepareCancelFlush(): Boolean = synchronized(lock) {
        if (activePointersMask != 0) {
            enqueueCancelLocked()
        }
        orderedQueue.isNotEmpty()
    }

    fun pollInto(packet: ControlPacket): Boolean = synchronized(lock) {
        val slot = orderedQueue.removeFirstOrNull()
        val hasNext = slot != null
        if (slot != null) {
            if (slot.action == TouchAction.MOVE && pendingMoveByPointer[slot.pointer] === slot) {
                pendingMoveByPointer[slot.pointer] = null
            }
            packet.type = MessageType.TOUCH
            packet.pointer = slot.pointer
            packet.seq = 0
            packet.param = slot.action.id
            packet.x = slot.x
            packet.y = slot.y
            packet.payloadLength = 0
            TouchSlotPool.recycle(slotPool, slot)
        }
        hasNext
    }

    fun reset() {
        synchronized(lock) {
            activePointersMask = 0
            pendingMoveByPointer.fill(null)
            while (orderedQueue.isNotEmpty()) {
                TouchSlotPool.recycle(slotPool, orderedQueue.removeFirst())
            }
        }
    }

    private fun enqueueCancelLocked() {
        activePointersMask = 0
        pendingMoveByPointer.fill(null)
        TouchSlotPool.prunePendingMoves(orderedQueue, slotPool)
        enqueueSlotLocked(0, TouchAction.CANCEL, 0f, 0f)
    }

    private fun enqueueSlotLocked(
        pointerId: Int,
        action: TouchAction,
        x: Float,
        y: Float
    ): TouchSlot {
        val slot = TouchSlotPool.acquire(slotPool)
        slot.pointer = pointerId
        slot.action = action
        slot.x = TouchSlotPool.normalizeZero(x)
        slot.y = TouchSlotPool.normalizeZero(y)
        orderedQueue.addLast(slot)
        return slot
    }

    private companion object {
        const val POINTER_SLOTS = ProtocolV2.MAX_POINTER_ID + 1
    }
}
