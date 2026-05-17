package org.dexpace.sdk.core.pipeline.step.retry

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow

/**
 * Pure delay computation for [RetryStep]. Combines:
 *  - Square's exponential backoff with symmetric jitter (`delay = initial * multiplier^(n-1)`,
 *    then sampled uniformly from `[delay * (1 - j/2), delay * (1 + j/2)]`).
 *  - gax's per-attempt deadline shrinking — the computed delay is capped so the next
 *    attempt cannot push the cumulative elapsed time past [RetrySettings.totalTimeout].
 *
 * ## Precedence
 *
 *  1. If a server-supplied `Retry-After` / `X-RateLimit-Reset` hint is present, it overrides
 *     the exponential schedule. The hint is **still** clamped by the deadline so the budget
 *     stays bounded.
 *  2. Otherwise, the exponential value is computed, jittered, and clamped.
 *
 * ## Thread-safety
 *
 * Stateless — safe to call concurrently. Uses [ThreadLocalRandom] for the jitter draw so
 * the jitter source itself is thread-safe.
 */
public object BackoffCalculator {
    /**
     * Computes the delay before the next retry.
     *
     * @param attempt 1-indexed attempt number — `1` represents the delay before the **first
     *   retry** (i.e. after the initial send has failed). `0` and negative values are
     *   rejected because the caller should not invoke the backoff calculator before an
     *   attempt has actually failed.
     * @param settings Retry configuration (initial delay, multiplier, max, jitter, total
     *   timeout).
     * @param retryAfterHint Optional server-supplied delay (from `Retry-After` /
     *   `X-RateLimit-Reset` headers). When non-null this value is used as the unjittered
     *   delay; the deadline cap still applies. The hint replaces — not augments — the
     *   exponential schedule for this single decision.
     * @param elapsed Time already spent across previous attempts and delays. Used together
     *   with [RetrySettings.totalTimeout] to compute the deadline cap.
     * @return The capped, optionally jittered delay. Never negative; may be zero when the
     *   deadline is exhausted but the caller has not yet observed it.
     * @throws IllegalArgumentException if [attempt] < 1.
     */
    @JvmStatic
    @JvmOverloads
    public fun computeDelay(
        attempt: Int,
        settings: RetrySettings,
        retryAfterHint: Duration? = null,
        elapsed: Duration = Duration.ZERO,
    ): Duration {
        require(attempt >= 1) { "attempt must be ≥ 1 (got $attempt)" }
        val baseNanos =
            if (retryAfterHint != null) {
                // Server-supplied hint wins; we do NOT apply symmetric jitter because the
                // X-RateLimit-Reset path already adds positive jitter inside the parser,
                // and Retry-After is meant to be respected literally per RFC 7231.
                retryAfterHint.toNanos().coerceAtLeast(0L)
            } else {
                val scaledNanos = exponentialNanos(attempt, settings)
                applyJitter(scaledNanos, settings.jitter)
            }
        return clampToDeadline(Duration.ofNanos(baseNanos), settings.totalTimeout, elapsed)
    }

    /**
     * Computes the unjittered exponential delay for the [attempt]-th retry, capped at
     * [RetrySettings.maxDelay]. Returned in nanoseconds for downstream jitter math.
     *
     * Overflow guard: extremely large attempt counts paired with a non-trivial multiplier
     * can produce values that overflow `Long`. We catch that by funneling through `Double`
     * before clamping, which saturates to `+∞` and then clamps to the configured max.
     */
    private fun exponentialNanos(
        attempt: Int,
        settings: RetrySettings,
    ): Long {
        val initialNanos = settings.initialDelay.toNanos().toDouble()
        // attempt is 1-indexed; the first retry uses initialDelay * multiplier^0 = initialDelay.
        val scaled = initialNanos * settings.delayMultiplier.pow(attempt - 1)
        val maxNanos = settings.maxDelay.toNanos()
        return when {
            scaled.isNaN() -> 0L
            scaled.isInfinite() -> maxNanos
            scaled >= maxNanos.toDouble() -> maxNanos
            scaled < 0.0 -> 0L
            else -> scaled.toLong()
        }
    }

    /**
     * Applies symmetric jitter to [baseNanos]. Returns a value drawn uniformly from
     * `[baseNanos * (1 - jitter/2), baseNanos * (1 + jitter/2)]`.
     *
     * `jitter = 0` returns [baseNanos] unchanged. `jitter = 1.0` produces a draw from
     * `[0, 2 * baseNanos]`. Negative results are clamped to zero defensively.
     */
    private fun applyJitter(
        baseNanos: Long,
        jitter: Double,
    ): Long {
        if (jitter <= 0.0 || baseNanos <= 0L) return baseNanos
        val halfRange = baseNanos.toDouble() * (jitter / 2.0)
        // nextDouble(origin, bound) — origin inclusive, bound exclusive; we use the symmetric
        // range so the midpoint stays at baseNanos.
        val origin = baseNanos.toDouble() - halfRange
        val bound = baseNanos.toDouble() + halfRange
        return if (origin >= bound) {
            // Degenerate range (halfRange < 1 ns) — return base unchanged rather than
            // throwing from ThreadLocalRandom's "bound must be greater than origin" guard.
            baseNanos
        } else {
            val sampled = ThreadLocalRandom.current().nextDouble(origin, bound)
            sampled.toLong().coerceAtLeast(0L)
        }
    }

    /**
     * Clamps [delay] so that `elapsed + delay <= totalTimeout`. When the deadline is
     * already exhausted (`elapsed >= totalTimeout`), returns `Duration.ZERO` — the caller
     * checks the deadline before scheduling the wait and aborts the retry chain.
     */
    private fun clampToDeadline(
        delay: Duration,
        totalTimeout: Duration,
        elapsed: Duration,
    ): Duration {
        if (totalTimeout.isZero) return delay
        val remaining = totalTimeout.minus(elapsed)
        if (remaining.isNegative || remaining.isZero) return Duration.ZERO
        return if (delay > remaining) remaining else delay
    }
}
