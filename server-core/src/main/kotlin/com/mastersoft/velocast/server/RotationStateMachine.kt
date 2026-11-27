package com.mastersoft.velocast.server

import com.mastersoft.velocast.protocol.DisplayGeometry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks display geometry changes via an optional watcher signal plus bounded 500 ms polling
 * (FUN-01). Uses [ServerClock] so unit tests can drive transitions deterministically.
 */
class RotationStateMachine(
    initialGeometry: DisplayGeometry,
    private val clock: ServerClock,
    private val queryGeometry: () -> DisplayGeometry,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    private val watcherSignaled = AtomicBoolean(false)
    private var currentGeometry: DisplayGeometry = initialGeometry
    private var lastPollMs: Long = clock.monotonicMillis()

    init {
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive: $pollIntervalMs" }
    }

    val current: DisplayGeometry
        get() = currentGeometry

    fun notifyRotationChanged() {
        watcherSignaled.set(true)
    }

    fun updateBaseline(geometry: DisplayGeometry) {
        currentGeometry = geometry
        lastPollMs = clock.monotonicMillis()
        watcherSignaled.set(false)
    }

    /**
     * Queries the live display geometry if the watcher fired or [pollIntervalMs] has elapsed.
     * Returns the new [DisplayGeometry] when width, height, or rotation changed; otherwise `null`.
     */
    fun pollIfDue(): DisplayGeometry? {
        val nowMs = clock.monotonicMillis()
        val signaled = watcherSignaled.getAndSet(false)
        if (!signaled && nowMs - lastPollMs < pollIntervalMs) {
            return null
        }
        lastPollMs = nowMs
        val latest = queryGeometry()
        return if (latest != currentGeometry) {
            currentGeometry = latest
            latest
        } else {
            null
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 500L
    }
}
