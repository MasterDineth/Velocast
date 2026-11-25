package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.Authenticator
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Schedules a one-shot timeout callback; abstracted so handshake timeout tests run deterministically
 * without sleeping for real wall-clock seconds.
 */
interface HandshakeTimer {
    fun schedule(delayMs: Long, onTimeout: () -> Unit): AutoCloseable
}

class ScheduledHandshakeTimer :
    HandshakeTimer,
    Closeable {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "velocast-handshake-timer").apply { isDaemon = true }
        }

    override fun schedule(delayMs: Long, onTimeout: () -> Unit): AutoCloseable {
        val future = scheduler.schedule(onTimeout, delayMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}

/**
 * Tracks failed handshake attempts per remote IP with bounded LRU storage and exponential
 * back-off (`baseBackoffMs * 2^(failures - 1)` capped at `maxBackoffMs`).
 */
class PeerBackoffTracker(
    private val clock: ServerClock,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val maxPeers: Int = DEFAULT_MAX_PEERS
) {
    private data class PeerState(var failures: Int = 0, var blockedUntilMs: Long = 0L)

    private val lock = Any()
    private val peers = object : LinkedHashMap<InetAddress, PeerState>(
        INITIAL_CAPACITY,
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<InetAddress, PeerState>
        ): Boolean = size > maxPeers
    }

    fun backoffRemainingMs(address: InetAddress): Long = synchronized(lock) {
        val entry = peers[address] ?: return 0L
        val remaining = entry.blockedUntilMs - clock.monotonicMillis()
        if (remaining > 0L) remaining else 0L
    }

    fun failureCount(address: InetAddress): Int = synchronized(lock) {
        peers[address]?.failures ?: 0
    }

    fun recordFailure(address: InetAddress): Long = synchronized(lock) {
        val entry = peers.getOrPut(address) { PeerState() }
        entry.failures++
        val shift = (entry.failures - 1).coerceAtMost(MAX_SHIFT)
        val delay = (baseBackoffMs shl shift).coerceAtMost(maxBackoffMs)
        entry.blockedUntilMs = clock.monotonicMillis() + delay
        delay
    }

    fun recordSuccess(address: InetAddress) {
        synchronized(lock) {
            peers.remove(address)
        }
    }

    companion object {
        const val DEFAULT_BASE_BACKOFF_MS = 1_000L
        const val DEFAULT_MAX_BACKOFF_MS = 60_000L
        const val DEFAULT_MAX_PEERS = 256
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_SHIFT = 16
    }
}

/**
 * Coordinates connection acceptance, per-IP exponential back-off, handshake deadlines, and
 * single-active-session replacement (SEC-01, SEC-03, CQ-04).
 */
class SessionGuard(
    private val acceptor: ServerAcceptor,
    private val authenticator: Authenticator,
    private val environment: SessionEnvironment,
    private val clock: ServerClock,
    private val logger: Logger,
    private val handshakeTimer: HandshakeTimer = ScheduledHandshakeTimer(),
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val sessionIdleTimeoutMs: Long = DEFAULT_SESSION_IDLE_TIMEOUT_MS,
    private val backoffTracker: PeerBackoffTracker = PeerBackoffTracker(clock),
    private val random: SecureRandom = SecureRandom(),
    maxConcurrentWorkers: Int = DEFAULT_MAX_WORKERS
) : Closeable {
    private val activationLock = Any()
    private val nextSessionId = AtomicLong(1L)
    private val workerCounter = AtomicInteger(1)
    private val inFlightSessions = Collections.synchronizedSet(HashSet<ServerSession>())

    @Volatile
    private var closed = false

    @Volatile
    private var activeSession: ServerSession? = null

    private val workerPool = ThreadPoolExecutor(
        CORE_WORKERS,
        maxConcurrentWorkers,
        WORKER_KEEP_ALIVE_SEC,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "velocast-session-${workerCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    )

    val currentActiveSession: ServerSession?
        get() = activeSession

    fun runAcceptLoop() {
        logger.info(TAG, "Listening on port ${acceptor.localPort}")
        while (!closed && !acceptor.isClosed) {
            val socket = tryAccept()
            if (socket != null) {
                if (closed) {
                    closeSocketQuietly(socket, "server closing")
                } else {
                    handleAcceptedSocket(socket)
                }
            }
        }
    }

    private fun handleAcceptedSocket(socket: PeerSocket) {
        val remainingBackoff = backoffTracker.backoffRemainingMs(socket.remoteAddress)
        if (remainingBackoff > 0L) {
            logger.warn(
                TAG,
                "Rejecting backed-off peer ${socket.remoteAddress.hostAddress} " +
                    "(${remainingBackoff}ms remaining)"
            )
            closeSocketQuietly(socket, "backed-off peer")
        } else {
            dispatchSession(socket)
        }
    }

    override fun close() {
        val sessionToClose: ServerSession?
        synchronized(activationLock) {
            if (closed) return
            closed = true
            sessionToClose = activeSession
            activeSession = null
        }
        try {
            acceptor.close()
        } catch (e: IOException) {
            logger.warn(TAG, "Error closing acceptor", e)
        }
        sessionToClose?.requestClose()
        val snapshot = synchronized(inFlightSessions) { inFlightSessions.toList() }
        for (session in snapshot) {
            session.requestClose()
        }
        workerPool.shutdownNow()
        try {
            workerPool.awaitTermination(SHUTDOWN_AWAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(TAG, "Interrupted awaiting worker pool shutdown", e)
        }
        if (handshakeTimer is Closeable) {
            try {
                handshakeTimer.close()
            } catch (e: IOException) {
                logger.warn(TAG, "Error closing handshake timer", e)
            }
        }
    }

    private fun tryAccept(): PeerSocket? = try {
        acceptor.accept()
    } catch (e: IOException) {
        if (!closed && !acceptor.isClosed) {
            logger.warn(TAG, "Accept error on port ${acceptor.localPort}", e)
        }
        null
    }

    private fun dispatchSession(socket: PeerSocket) {
        val session = ServerSession(
            id = nextSessionId.getAndIncrement(),
            peerSocket = socket,
            authenticator = authenticator,
            environment = environment,
            clock = clock,
            logger = logger,
            handshakeTimer = handshakeTimer,
            handshakeTimeoutMs = handshakeTimeoutMs,
            sessionIdleTimeoutMs = sessionIdleTimeoutMs,
            random = random,
            onActivate = ::activateAuthenticatedSession,
            onAuthFailed = ::recordAuthFailure,
            onClosed = ::handleSessionClosed
        )
        inFlightSessions.add(session)
        try {
            workerPool.execute { session.run() }
        } catch (e: RejectedExecutionException) {
            inFlightSessions.remove(session)
            logger.warn(
                TAG,
                "Rejecting connection from ${socket.remoteAddress.hostAddress}: busy",
                e
            )
            closeSocketQuietly(socket, "worker pool saturated")
        }
    }

    private fun activateAuthenticatedSession(incoming: ServerSession): Boolean {
        val previous = synchronized(activationLock) {
            if (closed) return false
            val existing = activeSession
            if (existing !== null && existing !== incoming) {
                existing
            } else {
                null
            }
        }
        if (previous != null) {
            logger.info(
                TAG,
                "Preempting active session ${previous.id} for authenticated session ${incoming.id}"
            )
            previous.closeAndAwait()
        }
        return synchronized(activationLock) {
            if (closed) {
                false
            } else if (!incoming.markActiveFromGuard()) {
                false
            } else {
                activeSession = incoming
                backoffTracker.recordSuccess(incoming.peerSocket.remoteAddress)
                true
            }
        }
    }

    private fun recordAuthFailure(address: InetAddress) {
        if (closed) return
        val delayMs = backoffTracker.recordFailure(address)
        logger.warn(
            TAG,
            "Authentication failed from ${address.hostAddress}; back-off ${delayMs}ms"
        )
    }

    private fun handleSessionClosed(session: ServerSession) {
        inFlightSessions.remove(session)
        synchronized(activationLock) {
            if (activeSession === session) {
                activeSession = null
            }
        }
    }

    private fun closeSocketQuietly(socket: PeerSocket, reason: String) {
        try {
            socket.close()
        } catch (e: IOException) {
            logger.debug(TAG, "Error closing socket ($reason)", e)
        }
    }

    companion object {
        private const val TAG = "SessionGuard"
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 3_000L
        const val DEFAULT_SESSION_IDLE_TIMEOUT_MS = 20_000L
        const val DEFAULT_MAX_WORKERS = 16
        private const val CORE_WORKERS = 2
        private const val WORKER_KEEP_ALIVE_SEC = 30L
        private const val SHUTDOWN_AWAIT_MS = 2_000L
    }
}
