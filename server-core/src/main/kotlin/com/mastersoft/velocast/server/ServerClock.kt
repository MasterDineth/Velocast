package com.mastersoft.velocast.server

import android.os.SystemClock

/**
 * Clock abstraction so session timers, video capture timestamps, and touch event timestamps are
 * deterministic and unit-testable without Android framework calls.
 */
interface ServerClock {
    fun monotonicMillis(): Long
    fun monotonicMicros(): Long
    fun uptimeMillis(): Long
    fun wallTimeMillis(): Long
}

class AndroidServerClock : ServerClock {
    private val baseNanos: Long = System.nanoTime()

    override fun monotonicMillis(): Long =
        (System.nanoTime() - baseNanos).coerceAtLeast(0L) / NANOS_PER_MILLI

    override fun monotonicMicros(): Long =
        (System.nanoTime() - baseNanos).coerceAtLeast(0L) / NANOS_PER_MICRO

    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    override fun wallTimeMillis(): Long = System.currentTimeMillis()

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
