package org.dexpace.sdk.core.util

import java.time.Duration
import java.time.Instant

/**
 * Source of wall-clock time, monotonic time, and interruptible sleeps.
 *
 * Injected into time-dependent components (retry back-off, bearer-token expiry, redirect
 * timing) so tests can drive time deterministically via a `FixedClock` (in `testFixtures`)
 * without `Thread.sleep` or `Instant.now()` calls.
 *
 * Production code uses [SYSTEM]; tests pass a fake.
 */
interface Clock {
    /**
     * Returns the current wall-clock instant. May go backwards if the OS clock is adjusted.
     * For elapsed-time measurement, use [monotonic] instead.
     */
    fun now(): Instant

    /**
     * Returns a monotonically non-decreasing nanosecond counter suitable for measuring
     * elapsed durations. The absolute value is not meaningful — only deltas between calls
     * are.
     */
    fun monotonic(): Long

    /**
     * Blocks the current thread for [duration]. A zero duration is allowed and may return
     * immediately or yield. Honors `Thread.interrupt()` per Rank 22.
     *
     * @throws IllegalArgumentException if [duration] is negative.
     * @throws InterruptedException if the calling thread is interrupted while sleeping.
     *         The thread's interrupt status is left as set by `Thread.sleep` — the JVM
     *         clears it on the thrown `InterruptedException`, matching standard semantics.
     */
    @Throws(InterruptedException::class)
    fun sleep(duration: Duration)

    companion object {
        /** Shared system clock backed by [Instant.now], [System.nanoTime], and [Thread.sleep]. */
        @JvmField
        val SYSTEM: Clock = SystemClock()
    }
}

private class SystemClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun monotonic(): Long = System.nanoTime()

    @Throws(InterruptedException::class)
    override fun sleep(duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative (got $duration)" }
        Thread.sleep(duration.toMillis())
    }
}
