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
public interface Clock {
    /**
     * Returns the current wall-clock instant. May go backwards if the OS clock is adjusted.
     * For elapsed-time measurement, use [monotonic] instead.
     */
    public fun now(): Instant

    /**
     * Returns a monotonically non-decreasing nanosecond counter suitable for measuring
     * elapsed durations. The absolute value is not meaningful — only deltas between calls
     * are.
     */
    public fun monotonic(): Long

    /**
     * Blocks the current thread for [duration]. A zero duration is allowed and may return
     * immediately or yield. Honors `Thread.interrupt()` per Rank 22.
     *
     * Implementations honor sub-millisecond precision where the underlying platform allows
     * it (the [SYSTEM] implementation forwards the nanosecond remainder to `Thread.sleep`).
     *
     * @throws IllegalArgumentException if [duration] is negative.
     * @throws InterruptedException if the calling thread is interrupted while sleeping.
     *         Per the SDK cancellation contract, implementations re-assert the thread's
     *         interrupt status via `Thread.currentThread().interrupt()` before propagating
     *         the exception so downstream handlers observe the interrupted state.
     */
    @Throws(InterruptedException::class)
    public fun sleep(duration: Duration)

    public companion object {
        /** Shared system clock backed by [Instant.now], [System.nanoTime], and [Thread.sleep]. */
        @JvmField
        public val SYSTEM: Clock = SystemClock()
    }
}

private class SystemClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun monotonic(): Long = System.nanoTime()

    @Throws(InterruptedException::class)
    override fun sleep(duration: Duration) {
        require(!duration.isNegative) { "duration must be non-negative (got $duration)" }
        // `toMillis` truncates sub-millisecond precision; forward the leftover nanos so
        // callers asking for, e.g., 1_500_000 ns (1.5ms) don't lose half a millisecond.
        val millis = duration.toMillis()
        val nanos = duration.nano % NANOS_PER_MILLI
        try {
            Thread.sleep(millis, nanos)
        } catch (e: InterruptedException) {
            // Per the SDK cancellation contract: `Thread.sleep` clears the interrupt flag
            // when it throws; re-assert it so downstream handlers see the interrupted state.
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000
    }
}
