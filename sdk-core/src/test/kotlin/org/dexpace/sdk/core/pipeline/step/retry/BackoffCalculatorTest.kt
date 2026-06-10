/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [BackoffCalculator]. Verifies:
 *  - Exponential schedule (initial * multiplier^(n-1)) caps at maxDelay.
 *  - Symmetric jitter stays within the configured fraction.
 *  - Server-supplied retry-after hint overrides the schedule.
 *  - Per-attempt deadline shrinking clamps delays against the totalTimeout budget.
 *  - Invalid attempt numbers are rejected.
 */
class BackoffCalculatorTest {
    /** No-jitter settings to make the exponential schedule deterministic. */
    private val noJitterSettings: RetrySettings =
        RetrySettings.builder()
            .initialDelay(Duration.ofMillis(BASE_DELAY_MS))
            .delayMultiplier(BASE_MULTIPLIER)
            .maxDelay(Duration.ofMillis(MAX_DELAY_MS))
            .maxAttempts(MAX_ATTEMPTS_DEFAULT)
            .totalTimeout(Duration.ZERO)
            .jitter(0.0)
            .build()

    // region -- exponential schedule --

    @Test
    fun `attempt 1 returns the initial delay`() {
        val delay = BackoffCalculator.computeDelay(1, noJitterSettings)
        assertEquals(Duration.ofMillis(BASE_DELAY_MS), delay)
    }

    @Test
    fun `attempts 2 through 5 follow the exponential schedule`() {
        // Indexed by (attempt - 1). Attempts 4+ saturate at maxDelay (the scaled value
        // 800ms is capped at 600ms).
        val expected =
            longArrayOf(
                BASE_DELAY_MS,
                BASE_DELAY_MS * 2,
                BASE_DELAY_MS * 4,
                MAX_DELAY_MS,
                MAX_DELAY_MS,
            )
        for (attempt in 1..5) {
            val delay = BackoffCalculator.computeDelay(attempt, noJitterSettings)
            assertEquals(
                Duration.ofMillis(expected[attempt - 1]),
                delay,
                "attempt $attempt: expected ${expected[attempt - 1]} ms, got ${delay.toMillis()} ms",
            )
        }
    }

    @Test
    fun `attempt below 1 throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackoffCalculator.computeDelay(0, noJitterSettings)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackoffCalculator.computeDelay(-1, noJitterSettings)
        }
    }

    @Test
    fun `huge attempt count saturates to maxDelay rather than overflowing`() {
        val delay = BackoffCalculator.computeDelay(LARGE_ATTEMPT, noJitterSettings)
        assertEquals(Duration.ofMillis(MAX_DELAY_MS), delay)
    }

    // endregion

    // region -- jitter --

    @Test
    fun `symmetric jitter stays within configured bounds`() {
        val settings = noJitterSettings.newBuilder().jitter(JITTER_FRACTION).build()
        val baseNanos = Duration.ofMillis(BASE_DELAY_MS).toNanos()
        val halfRange = (baseNanos.toDouble() * (JITTER_FRACTION / 2.0)).toLong()
        val lower = baseNanos - halfRange
        val upper = baseNanos + halfRange
        repeat(JITTER_SAMPLES) {
            val delay = BackoffCalculator.computeDelay(1, settings)
            val nanos = delay.toNanos()
            assertTrue(nanos in lower..upper, "jitter $nanos must be in [$lower, $upper]")
        }
    }

    @Test
    fun `zero jitter returns the schedule value exactly`() {
        val delay = BackoffCalculator.computeDelay(2, noJitterSettings)
        assertEquals(Duration.ofMillis(BASE_DELAY_MS * 2), delay)
    }

    // endregion

    // region -- retryAfterHint --

    @Test
    fun `retryAfterHint overrides the exponential schedule`() {
        val hint = Duration.ofSeconds(2)
        val delay = BackoffCalculator.computeDelay(1, noJitterSettings, hint)
        assertEquals(hint, delay)
    }

    @Test
    fun `retryAfterHint of zero produces zero delay`() {
        val delay = BackoffCalculator.computeDelay(1, noJitterSettings, Duration.ZERO)
        assertEquals(Duration.ZERO, delay)
    }

    @Test
    fun `negative retryAfterHint is clamped to zero`() {
        val delay = BackoffCalculator.computeDelay(1, noJitterSettings, Duration.ofSeconds(-1))
        assertEquals(Duration.ZERO, delay)
    }

    @Test
    fun `huge retryAfterHint is clamped instead of overflowing toNanos`() {
        // Duration.ofSeconds(Long.MAX_VALUE).toNanos() throws ArithmeticException; computeDelay
        // must clamp the hint to its 365-day ceiling rather than propagate the overflow.
        val hugeHint = Duration.ofSeconds(Long.MAX_VALUE)
        // totalTimeout = ZERO disables the deadline cap, so the clamped hint is returned as-is.
        val delay = BackoffCalculator.computeDelay(1, noJitterSettings, hugeHint)
        assertEquals(Duration.ofDays(MAX_HINT_DAYS), delay, "Huge hint must clamp to the 365-day ceiling")
    }

    @Test
    fun `huge retryAfterHint does not throw even with a deadline cap`() {
        val settings = noJitterSettings.newBuilder().totalTimeout(Duration.ofSeconds(5)).build()
        val hugeHint = Duration.ofSeconds(Long.MAX_VALUE)
        // The clamp keeps toNanos() in range; the deadline cap then shrinks it to the budget.
        val delay = BackoffCalculator.computeDelay(1, settings, hugeHint)
        assertEquals(Duration.ofSeconds(5), delay)
    }

    // endregion

    // region -- deadline cap --

    @Test
    fun `delay capped against totalTimeout budget`() {
        val settings = noJitterSettings.newBuilder().totalTimeout(Duration.ofMillis(BUDGET_MS_TIGHT)).build()
        val elapsed = Duration.ofMillis(ELAPSED_MS)
        val delay = BackoffCalculator.computeDelay(1, settings, retryAfterHint = null, elapsed = elapsed)
        // Initial delay 100 ms would exceed remaining budget of 80 ms; expect cap.
        assertEquals(Duration.ofMillis(BUDGET_MS_TIGHT - ELAPSED_MS), delay)
    }

    @Test
    fun `delay returns zero when elapsed already exceeds totalTimeout`() {
        val settings = noJitterSettings.newBuilder().totalTimeout(Duration.ofMillis(BUDGET_MS_TIGHT)).build()
        val elapsed = Duration.ofMillis(BUDGET_MS_TIGHT + 1)
        val delay = BackoffCalculator.computeDelay(1, settings, retryAfterHint = null, elapsed = elapsed)
        assertEquals(Duration.ZERO, delay)
    }

    @Test
    fun `zero totalTimeout disables deadline cap`() {
        // Even with a huge elapsed, a zero totalTimeout means no clamping.
        val settings = noJitterSettings.newBuilder().totalTimeout(Duration.ZERO).build()
        val elapsed = Duration.ofHours(1)
        val delay = BackoffCalculator.computeDelay(1, settings, retryAfterHint = null, elapsed = elapsed)
        assertEquals(Duration.ofMillis(BASE_DELAY_MS), delay)
    }

    @Test
    fun `retryAfterHint also clamped against deadline`() {
        val settings = noJitterSettings.newBuilder().totalTimeout(Duration.ofMillis(BUDGET_MS_TIGHT)).build()
        val elapsed = Duration.ofMillis(ELAPSED_MS)
        // Hint is 500 ms — much larger than remaining budget of 80 ms.
        val delay =
            BackoffCalculator.computeDelay(1, settings, retryAfterHint = Duration.ofMillis(500), elapsed = elapsed)
        assertEquals(Duration.ofMillis(BUDGET_MS_TIGHT - ELAPSED_MS), delay)
    }

    // endregion

    // region -- saturating nanos conversion (defense-in-depth) --

    @Test
    fun `toNanosSaturating returns the exact nanos for an in-range duration`() {
        assertEquals(1_500_000_000L, Duration.ofMillis(1500).toNanosSaturating())
    }

    @Test
    fun `toNanosSaturating accepts the Long MAX_VALUE nanos boundary`() {
        assertEquals(Long.MAX_VALUE, Duration.ofNanos(Long.MAX_VALUE).toNanosSaturating())
    }

    @Test
    fun `toNanosSaturating saturates instead of throwing when the duration overflows nanos`() {
        // Duration.ofSeconds(Long.MAX_VALUE).toNanos() throws ArithmeticException; the saturating
        // variant returns Long.MAX_VALUE so the backoff math stays total even for an out-of-range
        // settings object built outside the (now-guarded) builder.
        assertEquals(Long.MAX_VALUE, Duration.ofSeconds(Long.MAX_VALUE).toNanosSaturating())
    }

    // endregion

    private companion object {
        // Schedule constants.
        private const val BASE_DELAY_MS = 100L
        private const val BASE_MULTIPLIER = 2.0
        private const val MAX_DELAY_MS = 600L
        private const val MAX_ATTEMPTS_DEFAULT = 5
        private const val LARGE_ATTEMPT = 100

        // Jitter constants.
        private const val JITTER_FRACTION = 0.5
        private const val JITTER_SAMPLES = 50

        // Deadline constants.
        private const val BUDGET_MS_TIGHT = 100L
        private const val ELAPSED_MS = 20L

        // Hint-clamp ceiling (must match BackoffCalculator.MAX_HINT).
        private const val MAX_HINT_DAYS = 365L
    }
}
