package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.CodecConfig
import com.mastersoft.velocast.protocol.FrameFlags
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Payload
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolException
import com.mastersoft.velocast.protocol.SessionCipher
import com.mastersoft.velocast.protocol.VideoSize
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes outbound protocol v2 frames on a single connection.
 *
 * - During [Phase.HANDSHAKE], writes execute synchronously on the caller's thread.
 * - During [Phase.SESSION], a dedicated writer thread drains three priority lanes in order:
 *   1. Priority control messages (`PONG`, `STATUS`, `ERROR`, `DEVICE_INFO`, `BYE`).
 *   2. Stream geometry/config headers (`VIDEO_SIZE`, then `CONFIG`).
 *   3. Bounded 3-frame latest-wins [FrameQueue] for `VIDEO` frames.
 *
 * Video producers never block on socket I/O.
 */
class SessionFrameSink(
    private val writer: FrameWriter,
    private val out: OutputStream,
    queueCapacity: Int = FrameQueue.DEFAULT_CAPACITY
) {
    private class QueuedMessage(val payload: Payload, val flags: Int?)

    private val queueLock = ReentrantLock()
    private val hasWork = queueLock.newCondition()
    private val writerFinished = queueLock.newCondition()
    private val syncWriteLock = Any()

    private val priorityQueue = ArrayDeque<QueuedMessage>()
    private val streamHeaderQueue = ArrayDeque<QueuedMessage>()
    private val videoQueue = FrameQueue(
        capacity = queueCapacity,
        onFrameDropped = { onFrameDroppedCallback?.invoke() }
    )

    @Volatile
    private var onFrameDroppedCallback: (() -> Unit)? = null

    @Volatile
    private var onWriteFailureCallback: ((IOException) -> Unit)? = null

    private var activeLogger: Logger? = null
    private var sessionActive = false
    private var closing = false
    private var writerRunning = false
    private var writerThread: Thread? = null

    var phase: Phase
        get() = synchronized(syncWriteLock) { writer.phase }
        set(value) {
            synchronized(syncWriteLock) { writer.phase = value }
        }

    val queuedVideoDepth: Int
        get() = videoQueue.size

    val maxObservedVideoDepth: Int
        get() = videoQueue.maxObservedDepth

    val totalDroppedVideoFrames: Long
        get() = videoQueue.totalDroppedFrames

    fun setOnFrameDropped(callback: (() -> Unit)?) {
        onFrameDroppedCallback = callback
    }

    fun activateSession(
        sessionId: Long,
        logger: Logger,
        cipher: SessionCipher? = null,
        onFrameDropped: (() -> Unit)? = null,
        onWriteError: ((IOException) -> Unit)? = null
    ) {
        onFrameDroppedCallback = onFrameDropped
        onWriteFailureCallback = onWriteError
        synchronized(syncWriteLock) {
            writer.cipher = cipher
            writer.phase = Phase.SESSION
        }
        queueLock.withLock {
            if (sessionActive || closing) return
            activeLogger = logger
            sessionActive = true
            writerRunning = true
            writerThread =
                Thread({ WriterLoopRunner().run() }, "velocast-writer-$sessionId").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    fun writeMessage(msg: Payload, flags: Int? = null) {
        val isAsync = queueLock.withLock { sessionActive }
        if (!isAsync) {
            synchronized(syncWriteLock) {
                WriterIoHelpers.writeMessageDirect(writer, msg, flags)
                out.flush()
            }
            return
        }
        enqueuePriority(msg, flags)
    }

    fun enqueuePriority(msg: Payload, flags: Int? = null) {
        queueLock.withLock {
            if (closing) return
            if (!sessionActive) {
                synchronized(syncWriteLock) {
                    WriterIoHelpers.writeMessageDirect(writer, msg, flags)
                    out.flush()
                }
                return
            }
            priorityQueue.addLast(QueuedMessage(msg, flags))
            hasWork.signalAll()
        }
    }

    /**
     * Atomically clears stale unsent frames and queues `VIDEO_SIZE` then `CONFIG` so the client
     * always receives geometry and codec config before any frame at the new size (FUN-01).
     */
    fun beginStreamGeneration(videoSize: VideoSize, codecConfig: CodecConfig, isReset: Boolean) {
        val cfgFlags = if (isReset) FrameFlags.CONFIG or FrameFlags.RESET else FrameFlags.CONFIG
        queueLock.withLock {
            if (closing) return
            videoQueue.clear()
            streamHeaderQueue.clear()
            streamHeaderQueue.addLast(QueuedMessage(videoSize, FrameFlags.RESET))
            streamHeaderQueue.addLast(QueuedMessage(codecConfig, cfgFlags))
            if (!sessionActive) {
                synchronized(syncWriteLock) {
                    while (streamHeaderQueue.isNotEmpty()) {
                        val item = streamHeaderQueue.removeFirst()
                        WriterIoHelpers.writeMessageDirect(writer, item.payload, item.flags)
                    }
                    out.flush()
                }
            } else {
                hasWork.signalAll()
            }
        }
    }

    fun acquireVideoSlot(payloadBytes: Int): VideoFrameSlot = videoQueue.acquireSlot(payloadBytes)

    fun enqueueVideo(slot: VideoFrameSlot): Boolean {
        val shouldWriteSync = queueLock.withLock { !sessionActive && !closing }
        if (shouldWriteSync) {
            synchronized(syncWriteLock) {
                WriterIoHelpers.writeVideoSlotDirect(writer, FrameHeader(), slot, droppedBefore = 0)
                out.flush()
            }
            videoQueue.releaseSlot(slot)
            return false
        }
        val dropped = videoQueue.enqueue(slot)
        queueLock.withLock {
            hasWork.signalAll()
        }
        return dropped
    }

    fun clearMediaQueue(): Int = queueLock.withLock {
        streamHeaderQueue.clear()
        videoQueue.clear()
    }

    fun flushAndClose(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        val threadToJoin: Thread?
        queueLock.withLock {
            closing = true
            threadToJoin = writerThread
            hasWork.signalAll()
            awaitWriterTerminationLocked(timeoutMs)
            priorityQueue.clear()
            streamHeaderQueue.clear()
            videoQueue.clear()
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread() &&
            threadToJoin.isAlive
        ) {
            threadToJoin.interrupt()
        }
        synchronized(syncWriteLock) {
            writer.cipher?.close()
            writer.cipher = null
        }
    }

    private fun awaitWriterTerminationLocked(timeoutMs: Long) {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var doneWaiting = false
        while (writerRunning && !doneWaiting) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) {
                doneWaiting = true
            } else {
                try {
                    writerFinished.awaitNanos(remainingNs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    activeLogger?.debug(TAG, "Interrupted waiting for frame writer flush", e)
                    doneWaiting = true
                }
            }
        }
    }

    private inner class WriterLoopRunner {
        fun run() {
            val videoHeader = FrameHeader()
            try {
                var work = nextWorkItem()
                while (work != null) {
                    dispatchWorkItem(work, videoHeader)
                    work = nextWorkItem()
                }
            } catch (e: IOException) {
                if (!closing) {
                    activeLogger?.info(TAG, "Writer stream ended", e)
                    onWriteFailureCallback?.invoke(e)
                }
            } catch (e: ProtocolException) {
                activeLogger?.error(TAG, "Protocol violation in outbound writer", e)
            } finally {
                queueLock.withLock {
                    writerRunning = false
                    writerThread = null
                    writerFinished.signalAll()
                }
            }
        }

        private fun nextWorkItem(): Any? = queueLock.withLock {
            var item: Any? = pollNextLocked()
            while (item == null && !closing) {
                try {
                    hasWork.await()
                } catch (e: InterruptedException) {
                    activeLogger?.debug(TAG, "Writer wait interrupted", e)
                    if (closing) break
                }
                item = pollNextLocked()
            }
            item
        }

        private fun pollNextLocked(): Any? = priorityQueue.removeFirstOrNull()
            ?: streamHeaderQueue.removeFirstOrNull()
            ?: videoQueue.poll()

        private fun dispatchWorkItem(item: Any, videoHeader: FrameHeader) {
            when (item) {
                is QueuedMessage -> {
                    synchronized(syncWriteLock) {
                        WriterIoHelpers.writeMessageDirect(writer, item.payload, item.flags)
                        out.flush()
                    }
                }

                is DequeuedVideoFrame -> {
                    try {
                        synchronized(syncWriteLock) {
                            WriterIoHelpers.writeVideoSlotDirect(
                                writer,
                                videoHeader,
                                item.slot,
                                item.droppedBeforeFrame
                            )
                            out.flush()
                        }
                    } finally {
                        videoQueue.releaseSlot(item.slot)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "SessionFrameSink"
        const val DEFAULT_FLUSH_TIMEOUT_MS = 500L
    }
}

private object WriterIoHelpers {
    private const val SEQ_MASK = 0xFFFF
    private const val MAX_SEQ_GAP = 0x7FFE

    fun writeMessageDirect(writer: FrameWriter, msg: Payload, flags: Int?) {
        if (flags != null) {
            writer.writeMessage(msg, flags)
        } else {
            writer.writeMessage(msg)
        }
    }

    fun writeVideoSlotDirect(
        writer: FrameWriter,
        header: FrameHeader,
        slot: VideoFrameSlot,
        droppedBefore: Int
    ) {
        val advance = droppedBefore.coerceAtMost(MAX_SEQ_GAP)
        if (advance > 0) {
            writer.nextSeq = (writer.nextSeq + advance) and SEQ_MASK
        }
        header.type = MessageType.VIDEO
        header.flags = slot.flags
        header.seq = writer.nextSeq
        header.ptsUs = slot.ptsUs
        header.captureUs = slot.captureUs
        writer.writeInPlace(header, slot.buffer, slot.payloadLength)
        writer.nextSeq = (writer.nextSeq + 1) and SEQ_MASK
    }
}
