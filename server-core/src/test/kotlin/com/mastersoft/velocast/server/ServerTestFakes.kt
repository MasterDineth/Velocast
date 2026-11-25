package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.ControlPacket
import com.mastersoft.velocast.protocol.ControlReader
import com.mastersoft.velocast.protocol.ControlWriter
import com.mastersoft.velocast.protocol.DeviceInfo
import com.mastersoft.velocast.protocol.DisplayGeometry
import com.mastersoft.velocast.protocol.ErrorCode
import com.mastersoft.velocast.protocol.FrameHeader
import com.mastersoft.velocast.protocol.FrameReader
import com.mastersoft.velocast.protocol.FrameWriter
import com.mastersoft.velocast.protocol.LaunchMode
import com.mastersoft.velocast.protocol.MessageType
import com.mastersoft.velocast.protocol.Phase
import com.mastersoft.velocast.protocol.ProtocolV2
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FakeServerClock(initialMillis: Long = 1_000L) : ServerClock {
    private val monoMs = AtomicLong(initialMillis)
    private val uptimeMs = AtomicLong(initialMillis)
    private val wallMs = AtomicLong(1_700_000_000_000L + initialMillis)

    fun advanceBy(millis: Long) {
        monoMs.addAndGet(millis)
        uptimeMs.addAndGet(millis)
        wallMs.addAndGet(millis)
    }

    override fun monotonicMillis(): Long = monoMs.get()
    override fun monotonicMicros(): Long = monoMs.get() * 1_000L
    override fun uptimeMillis(): Long = uptimeMs.get()
    override fun wallTimeMillis(): Long = wallMs.get()
}

class FakeHandshakeTimer : HandshakeTimer {
    data class Entry(
        val delayMs: Long,
        val onTimeout: () -> Unit,
        @Volatile var cancelled: Boolean = false,
        @Volatile var fired: Boolean = false
    )

    val entries = CopyOnWriteArrayList<Entry>()

    override fun schedule(delayMs: Long, onTimeout: () -> Unit): AutoCloseable {
        val entry = Entry(delayMs, onTimeout)
        entries.add(entry)
        return AutoCloseable { entry.cancelled = true }
    }

    fun fireLatest() {
        val target = entries.lastOrNull { !it.cancelled && !it.fired } ?: return
        target.fired = true
        target.onTimeout()
    }

    fun fireAllActive() {
        for (entry in entries) {
            if (!entry.cancelled && !entry.fired) {
                entry.fired = true
                entry.onTimeout()
            }
        }
    }
}

class InMemoryPipeStream : AutoCloseable {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val buffer = ArrayDeque<Byte>()

    @Volatile
    var closed = false
        private set

    @Volatile
    var readTimeoutMs: Int = 0

    @Volatile
    var totalBytesRead: Long = 0L
        private set

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var bytesRead = 0
            if (len > 0) {
                lock.withLock {
                    val deadlineNs = if (readTimeoutMs > 0) {
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs.toLong())
                    } else {
                        0L
                    }
                    while (buffer.isEmpty() && !closed) {
                        if (readTimeoutMs > 0) {
                            val remainingNs = deadlineNs - System.nanoTime()
                            if (remainingNs <= 0L) {
                                throw SocketTimeoutException("Pipe read timed out")
                            }
                            notEmpty.awaitNanos(remainingNs)
                        } else {
                            notEmpty.await()
                        }
                    }
                    if (buffer.isEmpty() && closed) {
                        bytesRead = -1
                    } else {
                        val count = minOf(len, buffer.size)
                        for (i in 0 until count) {
                            b[off + i] = buffer.removeFirst()
                        }
                        totalBytesRead += count
                        bytesRead = count
                    }
                }
            }
            return bytesRead
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            lock.withLock {
                if (closed) throw IOException("Pipe closed")
                for (i in 0 until len) {
                    buffer.addLast(b[off + i])
                }
                notEmpty.signalAll()
            }
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }
}

class FakePeerSocket(
    override val remoteAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
) : PeerSocket {
    private val clientToServer = InMemoryPipeStream()
    private val serverToClient = InMemoryPipeStream()

    @Volatile
    private var closedFlag = false

    val clientOut: OutputStream get() = clientToServer.output
    val clientIn: InputStream get() = serverToClient.input
    val serverBytesRead: Long get() = clientToServer.totalBytesRead

    override val inputStream: InputStream get() = clientToServer.input
    override val outputStream: OutputStream get() = serverToClient.output
    override val isClosed: Boolean get() = closedFlag

    override fun setSoTimeout(timeoutMs: Int) {
        clientToServer.readTimeoutMs = timeoutMs
    }

    fun closeClientOutput() {
        clientToServer.close()
    }

    override fun close() {
        closedFlag = true
        clientToServer.close()
        serverToClient.close()
    }
}

class FakeServerAcceptor(override val localPort: Int = 27183) : ServerAcceptor {
    private val lock = ReentrantLock()
    private val hasSocket = lock.newCondition()
    private val pending = ArrayDeque<PeerSocket>()

    @Volatile
    private var closedFlag = false

    override val isClosed: Boolean get() = closedFlag

    fun enqueue(socket: PeerSocket) {
        lock.withLock {
            pending.addLast(socket)
            hasSocket.signalAll()
        }
    }

    override fun accept(): PeerSocket {
        lock.withLock {
            while (pending.isEmpty() && !closedFlag) {
                hasSocket.await()
            }
            if (closedFlag) throw IOException("Acceptor closed")
            return pending.removeFirst()
        }
    }

    override fun close() {
        lock.withLock {
            closedFlag = true
            hasSocket.signalAll()
        }
    }
}

class FakeInjectorSink : InjectorSink {
    data class TouchCall(
        val downTimeMs: Long,
        val eventTimeMs: Long,
        val action: Int,
        val pointerCount: Int,
        val idsSnapshot: IntArray,
        val xSnapshot: FloatArray,
        val ySnapshot: FloatArray,
        val idsArrayRef: IntArray,
        val xArrayRef: FloatArray,
        val yArrayRef: FloatArray
    )

    data class KeyCall(
        val downTimeMs: Long,
        val eventTimeMs: Long,
        val action: Int,
        val keyCode: Int
    )

    val touchCalls = CopyOnWriteArrayList<TouchCall>()
    val keyCalls = CopyOnWriteArrayList<KeyCall>()
    val actions = CopyOnWriteArrayList<String>()

    override fun injectTouch(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointerCount: Int,
        pointerIds: IntArray,
        xCoords: FloatArray,
        yCoords: FloatArray
    ) {
        touchCalls.add(
            TouchCall(
                downTimeMs = downTimeMs,
                eventTimeMs = eventTimeMs,
                action = action,
                pointerCount = pointerCount,
                idsSnapshot = pointerIds.copyOf(pointerCount),
                xSnapshot = xCoords.copyOf(pointerCount),
                ySnapshot = yCoords.copyOf(pointerCount),
                idsArrayRef = pointerIds,
                xArrayRef = xCoords,
                yArrayRef = yCoords
            )
        )
    }

    override fun injectKey(downTimeMs: Long, eventTimeMs: Long, action: Int, keyCode: Int) {
        keyCalls.add(KeyCall(downTimeMs, eventTimeMs, action, keyCode))
    }

    override fun expandNotificationShade() {
        actions.add("EXPAND_SHADE")
    }

    override fun collapseNotificationShade() {
        actions.add("COLLAPSE_SHADE")
    }

    override fun toggleScreenRotation() {
        actions.add("ROTATE")
    }
}

class FakeMediaPipeline(
    private val concurrentCounter: AtomicInteger,
    private val maxConcurrentCounter: AtomicInteger
) : MediaPipeline {
    val startCount = AtomicInteger(0)
    val pauseCount = AtomicInteger(0)
    val resumeCount = AtomicInteger(0)
    val keyframeReqCount = AtomicInteger(0)
    val reconfigures = CopyOnWriteArrayList<Triple<Int, Int, Float>>()

    @Volatile
    var closed = false
        private set

    override fun start() {
        startCount.incrementAndGet()
        val now = concurrentCounter.incrementAndGet()
        while (true) {
            val prev = maxConcurrentCounter.get()
            if (now <= prev || maxConcurrentCounter.compareAndSet(prev, now)) break
        }
    }

    override fun pause() {
        pauseCount.incrementAndGet()
    }

    override fun resume() {
        resumeCount.incrementAndGet()
    }

    override fun requestKeyframe() {
        keyframeReqCount.incrementAndGet()
    }

    override fun reconfigure(bitrateBps: Int, fps: Int, scale: Float) {
        reconfigures.add(Triple(bitrateBps, fps, scale))
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (startCount.get() > 0) {
                concurrentCounter.decrementAndGet()
            }
        }
    }
}

class FakeSessionEnvironment(
    private val clock: ServerClock,
    private val logger: Logger,
    override val capabilities: Int = 0
) : SessionEnvironment {
    val deviceInfoQueries = AtomicInteger(0)
    val injectorCreations = AtomicInteger(0)
    val pipelineCreations = AtomicInteger(0)
    val concurrentPipelines = AtomicInteger(0)
    val maxConcurrentPipelines = AtomicInteger(0)

    val sink = FakeInjectorSink()
    val createdPipelines = CopyOnWriteArrayList<FakeMediaPipeline>()
    val createdInjectors = CopyOnWriteArrayList<InputInjector>()

    val sampleDeviceInfo = DeviceInfo(
        sdkInt = 35,
        launchMode = LaunchMode.SHELL,
        display = DisplayGeometry(1080, 2400, 0),
        manufacturer = "Acme",
        model = "RescuePhone"
    )

    override fun queryDeviceInfo(): DeviceInfo {
        deviceInfoQueries.incrementAndGet()
        return sampleDeviceInfo
    }

    override fun createInjector(display: DisplayGeometry): InputInjector {
        injectorCreations.incrementAndGet()
        return InputInjector(display.width, display.height, sink, clock, logger).also {
            createdInjectors.add(it)
        }
    }

    override fun createMediaPipeline(
        display: DisplayGeometry,
        sink: SessionFrameSink
    ): MediaPipeline {
        pipelineCreations.incrementAndGet()
        return FakeMediaPipeline(concurrentPipelines, maxConcurrentPipelines).also {
            createdPipelines.add(it)
        }
    }
}

internal object TestClientDriver {
    val DEFAULT_SECRET: ByteArray = ByteArray(32) { (it + 1).toByte() }
    val CLIENT_NONCE: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }

    fun authenticate(
        socket: FakePeerSocket,
        secret: ByteArray = DEFAULT_SECRET,
        features: Int = 0
    ): Pair<ControlWriter, FrameReader> {
        val cw = ControlWriter(socket.clientOut)
        val fr = FrameReader(socket.clientIn)
        val client = com.mastersoft.velocast.protocol.ClientHandshake(
            HmacSha256Authenticator(secret),
            features,
            CLIENT_NONCE
        )
        cw.writePayload(client.hello())
        socket.clientOut.flush()

        val h = FrameHeader()
        check(fr.readHeader(h)) { "Expected SERVER_HELLO" }
        fr.readPayload(h)
        check(h.type == MessageType.SERVER_HELLO) { "Expected SERVER_HELLO, got ${h.type}" }

        cw.writePayload(client.onServerHello(fr.payload, 0, h.length))
        socket.clientOut.flush()

        check(fr.readHeader(h)) { "Expected server AUTH_PROOF" }
        fr.readPayload(h)
        check(h.type == MessageType.AUTH_PROOF) { "Expected AUTH_PROOF, got ${h.type}" }
        client.onServerProof(fr.payload, 0, h.length)

        cw.phase = Phase.SESSION
        fr.phase = Phase.SESSION

        check(fr.readHeader(h)) { "Expected DEVICE_INFO" }
        fr.readPayload(h)
        check(h.type == MessageType.DEVICE_INFO) { "Expected DEVICE_INFO, got ${h.type}" }
        return Pair(cw, fr)
    }

    fun expectFatalError(fr: FrameReader): ErrorCode {
        val h = FrameHeader()
        check(fr.readHeader(h)) { "Expected ERROR frame" }
        fr.readPayload(h)
        check(h.type == MessageType.ERROR) { "Expected ERROR, got ${h.type}" }
        val err = com.mastersoft.velocast.protocol.ErrorMessage.decode(fr.payload, 0, h.length)
        check(err.fatal) { "Expected fatal ERROR" }
        return err.code
    }

    fun ip(lastOctet: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, lastOctet.toByte()))
}
