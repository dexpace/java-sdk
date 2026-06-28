/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.generics.Builder
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Method
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService

// Largest delay representable as Long nanoseconds (~292 years). A larger value overflows
// Duration.toNanos() inside the backoff math, so the builder rejects it up front rather than
// letting an ArithmeticException surface mid-retry.
private val MAX_NANO_REPRESENTABLE_DELAY: Duration = Duration.ofNanos(Long.MAX_VALUE)

/**
 * Immutable configuration for [RetryStep].
 *
 * Combines the configurations of two reference implementations:
 *  - Square's `RetryInterceptor` — exponential backoff, `Retry-After` parsing, jitter.
 *  - gax's `ExponentialRetryAlgorithm` — per-attempt deadline shrinking against a total
 *    timeout, capped `maxDelay`, `maxAttempts`.
 *
 * ## Defaults (Square + gax tuned)
 *
 * These are the canonical SDK retry defaults, shared with the stage-based
 * [DefaultRetryStep][org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep] so both retry
 * stacks compute the same backoff schedule and honour the same retryable-status policy.
 *  - [totalTimeout] = 30 s — hard budget for all attempts combined.
 *  - [initialDelay] = 200 ms — first retry waits this long (subject to jitter).
 *  - [delayMultiplier] = 2.0 — each subsequent delay is multiplied by this factor.
 *  - [maxDelay] = 8 s — cap on the scaled delay; further attempts plateau here.
 *  - [maxAttempts] = 3 — total attempts including the initial call (matches Square's default).
 *  - [jitter] = 0.2 — symmetric jitter fraction. Effective delay ∈ `[delay*(1−j/2), delay*(1+j/2)]`.
 *  - [retryableStatuses] = `{408, 429, 500, 502, 503, 504}` — canonical retryable codes.
 *  - [retryableMethods] = `{GET, HEAD, OPTIONS, PUT, DELETE}` — safe-by-method per RFC 9110.
 *    `POST`/`PATCH`/etc. retry only when the request body is replayable.
 *  - [scheduler] = `null` — fall back to the lazy daemon scheduler created by [RetryStep].
 *  - [attemptHeaderName] = `null` — no per-attempt header is stamped (opt-in; see the property).
 *
 * ## Thread-safety
 *
 * Instances are immutable and safe to share across threads. Builders are **not** thread-safe;
 * confine to a single thread or guard externally.
 *
 * @property totalTimeout Hard ceiling on total time spent (delays + per-attempt work). A
 *   scheduled retry that would overshoot this budget is suppressed and the last failure is
 *   surfaced unchanged. `Duration.ZERO` disables the deadline.
 * @property initialDelay Delay before the first retry, prior to jitter and exponential
 *   scaling for subsequent attempts.
 * @property delayMultiplier Multiplier applied per attempt: `delay_n = initialDelay *
 *   delayMultiplier^(n-1)`, capped by [maxDelay].
 * @property maxDelay Upper bound on the scaled delay so unbounded growth cannot starve the
 *   total-timeout budget on the last attempts.
 * @property maxAttempts Total attempts including the first send. `1` disables retries.
 * @property jitter Symmetric jitter fraction in `[0, 1]`. `0` means no jitter; `1` means the
 *   delay is randomised over the full range `[0, 2*delay]`. The midpoint stays at `delay`.
 * @property retryableStatuses HTTP status codes that should trigger a retry when an
 *   [org.dexpace.sdk.core.http.response.exception.HttpException] surfaces with one of them.
 * @property retryableMethods HTTP methods that may be retried unconditionally (idempotent by
 *   RFC). Non-idempotent methods (`POST`, `PATCH`) only retry when the body is replayable.
 * @property scheduler Optional caller-provided scheduler. When `null` [RetryStep] uses a
 *   process-wide lazy daemon scheduler.
 * @property attemptHeaderName Optional request header stamped on each attempt [RetryStep]
 *   dispatches, carrying the 1-based attempt ordinal (`1` for the original send, `2` for the
 *   first retry, and so on) so servers and proxies can observe the retry count. `null` (the
 *   default) disables the header entirely. The header is set on a per-attempt copy of the
 *   request, never on the immutable template. Any idempotency key the caller stamps stays
 *   stable across retries — only this attempt header changes per send.
 */
public class RetrySettings
    // The 9-arg constructor lives behind a `private` modifier — public construction goes
    // through [RetrySettingsBuilder]. Suppress detekt's `LongParameterList` because the
    // parameter count is the actual settings cardinality and folding it into a wrapper
    // would just relocate the issue.
    @Suppress("LongParameterList")
    private constructor(
        public val totalTimeout: Duration,
        public val initialDelay: Duration,
        public val delayMultiplier: Double,
        public val maxDelay: Duration,
        public val maxAttempts: Int,
        public val jitter: Double,
        public val retryableStatuses: Set<Int>,
        public val retryableMethods: Set<Method>,
        public val scheduler: ScheduledExecutorService?,
        public val attemptHeaderName: HttpHeaderName?,
    ) {
        /** Returns a fresh [RetrySettingsBuilder] preloaded with this instance's values. */
        public fun newBuilder(): RetrySettingsBuilder = RetrySettingsBuilder(this)

        /**
         * Mutable builder for [RetrySettings]. Implements the generic [Builder] contract so it
         * can be folded by builder-style configuration code.
         */
        public class RetrySettingsBuilder : Builder<RetrySettings> {
            private var totalTimeout: Duration = DEFAULT_TOTAL_TIMEOUT
            private var initialDelay: Duration = DEFAULT_INITIAL_DELAY
            private var delayMultiplier: Double = DEFAULT_DELAY_MULTIPLIER
            private var maxDelay: Duration = DEFAULT_MAX_DELAY
            private var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
            private var jitter: Double = DEFAULT_JITTER
            private var retryableStatuses: Set<Int> = DEFAULT_RETRYABLE_STATUSES
            private var retryableMethods: Set<Method> = DEFAULT_RETRYABLE_METHODS
            private var scheduler: ScheduledExecutorService? = null
            private var attemptHeaderName: HttpHeaderName? = null

            /** Creates an empty builder populated with the SDK defaults. */
            public constructor()

            /** Creates a builder preloaded with the values from [settings]. */
            public constructor(settings: RetrySettings) {
                this.totalTimeout = settings.totalTimeout
                this.initialDelay = settings.initialDelay
                this.delayMultiplier = settings.delayMultiplier
                this.maxDelay = settings.maxDelay
                this.maxAttempts = settings.maxAttempts
                this.jitter = settings.jitter
                this.retryableStatuses = settings.retryableStatuses
                this.retryableMethods = settings.retryableMethods
                this.scheduler = settings.scheduler
                this.attemptHeaderName = settings.attemptHeaderName
            }

            /**
             * Validates a [Duration] setting: it must be non-negative and small enough that the
             * backoff math can convert it to nanoseconds without overflowing. Shared by the
             * [totalTimeout], [initialDelay], and [maxDelay] setters; [name] names the offending
             * field in the rejection message.
             */
            private fun requireRepresentable(
                name: String,
                value: Duration,
            ) {
                require(!value.isNegative) { "$name must be non-negative" }
                require(value <= MAX_NANO_REPRESENTABLE_DELAY) {
                    "$name must be representable in nanoseconds (≤ ~292 years); got $value"
                }
            }

            /** Sets [RetrySettings.totalTimeout]. Must be non-negative. */
            public fun totalTimeout(totalTimeout: Duration): RetrySettingsBuilder =
                apply {
                    requireRepresentable("totalTimeout", totalTimeout)
                    this.totalTimeout = totalTimeout
                }

            /** Sets [RetrySettings.initialDelay]. Must be non-negative. */
            public fun initialDelay(initialDelay: Duration): RetrySettingsBuilder =
                apply {
                    requireRepresentable("initialDelay", initialDelay)
                    this.initialDelay = initialDelay
                }

            /** Sets [RetrySettings.delayMultiplier]. Must be ≥ 1.0. */
            public fun delayMultiplier(delayMultiplier: Double): RetrySettingsBuilder =
                apply {
                    require(delayMultiplier >= 1.0) { "delayMultiplier must be ≥ 1.0 (got $delayMultiplier)" }
                    this.delayMultiplier = delayMultiplier
                }

            /** Sets [RetrySettings.maxDelay]. Must be non-negative. */
            public fun maxDelay(maxDelay: Duration): RetrySettingsBuilder =
                apply {
                    requireRepresentable("maxDelay", maxDelay)
                    this.maxDelay = maxDelay
                }

            /** Sets [RetrySettings.maxAttempts]. Must be ≥ 1 (1 disables retries). */
            public fun maxAttempts(maxAttempts: Int): RetrySettingsBuilder =
                apply {
                    require(maxAttempts >= 1) { "maxAttempts must be ≥ 1 (got $maxAttempts)" }
                    this.maxAttempts = maxAttempts
                }

            /** Sets [RetrySettings.jitter]. Must be in `[0.0, 1.0]`. */
            public fun jitter(jitter: Double): RetrySettingsBuilder =
                apply {
                    require(jitter in 0.0..1.0) { "jitter must be in [0.0, 1.0] (got $jitter)" }
                    this.jitter = jitter
                }

            /** Sets [RetrySettings.retryableStatuses]. A defensive copy is taken at [build]. */
            public fun retryableStatuses(retryableStatuses: Set<Int>): RetrySettingsBuilder =
                apply {
                    this.retryableStatuses = retryableStatuses
                }

            /** Sets [RetrySettings.retryableMethods]. A defensive copy is taken at [build]. */
            public fun retryableMethods(retryableMethods: Set<Method>): RetrySettingsBuilder =
                apply {
                    this.retryableMethods = retryableMethods
                }

            /**
             * Sets [RetrySettings.scheduler]. When `null` [RetryStep] uses a process-wide
             * lazy daemon scheduler. Caller-supplied schedulers are **not** shut down by
             * [RetryStep].
             */
            public fun scheduler(scheduler: ScheduledExecutorService?): RetrySettingsBuilder =
                apply {
                    this.scheduler = scheduler
                }

            /**
             * Sets [RetrySettings.attemptHeaderName]. When non-null, [RetryStep] stamps this
             * header (carrying the 1-based attempt ordinal) on each attempt's request copy.
             * `null` (the default) leaves attempts unstamped.
             */
            public fun attemptHeaderName(attemptHeaderName: HttpHeaderName?): RetrySettingsBuilder =
                apply {
                    this.attemptHeaderName = attemptHeaderName
                }

            /** Builds the immutable [RetrySettings] instance. */
            override fun build(): RetrySettings =
                RetrySettings(
                    totalTimeout = totalTimeout,
                    initialDelay = initialDelay,
                    delayMultiplier = delayMultiplier,
                    maxDelay = maxDelay,
                    maxAttempts = maxAttempts,
                    jitter = jitter,
                    retryableStatuses = LinkedHashSet(retryableStatuses),
                    retryableMethods = LinkedHashSet(retryableMethods),
                    scheduler = scheduler,
                    attemptHeaderName = attemptHeaderName,
                )
        }

        public companion object {
            // Defaults — see class kdoc for rationale.
            private const val DEFAULT_TOTAL_TIMEOUT_SECONDS = 30L
            private const val DEFAULT_INITIAL_DELAY_MS = 200L
            private const val DEFAULT_MAX_DELAY_SECONDS = 8L

            /**
             * Canonical exponential-backoff multiplier shared by both retry stacks: each delay is
             * `previous * 2.0`. Exposed so the stage-based [DefaultRetryStep][org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep]
             * and the recovery-aware [RetryStep] compute the same schedule from one constant.
             */
            public const val DEFAULT_DELAY_MULTIPLIER: Double = 2.0

            /**
             * Canonical symmetric jitter fraction shared by both retry stacks. The effective delay
             * is drawn uniformly from `[delay * (1 - j/2), delay * (1 + j/2)]`, i.e. ±10% at the
             * default `0.2`.
             */
            public const val DEFAULT_JITTER: Double = 0.2

            /**
             * Canonical retry budget shared by both stacks: **3 total attempts**, i.e. the initial
             * send plus 2 retries. The recovery-aware [RetryStep] counts total attempts directly
             * via [maxAttempts]; the stage-based step counts retries, so its equivalent default is
             * [DefaultRetryStep.DEFAULT_MAX_RETRIES][org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep.DEFAULT_MAX_RETRIES]
             * `= 2`. Both yield the same 3 sends.
             */
            public const val DEFAULT_MAX_ATTEMPTS: Int = 3

            /** Default total timeout: 30 s. */
            @JvmField
            public val DEFAULT_TOTAL_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_TOTAL_TIMEOUT_SECONDS)

            /** Default initial delay: 200 ms. Shared with the stage-based step's base delay. */
            @JvmField
            public val DEFAULT_INITIAL_DELAY: Duration = Duration.ofMillis(DEFAULT_INITIAL_DELAY_MS)

            /** Default max delay cap: 8 s. Shared with the stage-based step. */
            @JvmField
            public val DEFAULT_MAX_DELAY: Duration = Duration.ofSeconds(DEFAULT_MAX_DELAY_SECONDS)

            /**
             * Default retryable HTTP statuses: `408` (request timeout), `429` (rate limit), `500`
             * (internal), `502` (bad gateway), `503` (service unavailable), `504` (gateway
             * timeout).
             *
             * This is the recovery-aware step's allow-list; [RetryStep] intersects it with
             * [HttpException.isRetryable][org.dexpace.sdk.core.http.response.exception.HttpException.isRetryable]
             * (which is itself derived from [RetryUtils.isRetryable][org.dexpace.sdk.core.util.RetryUtils.isRetryable]).
             * With this default set the recovery-aware step retries the same common statuses the
             * stage-based [DefaultRetryStep][org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep]
             * does via `RetryUtils` — `408` included, so the two stacks agree on the 408 stance.
             * Callers wanting a stricter posture can pass a tighter set to the builder.
             */
            @JvmField
            public val DEFAULT_RETRYABLE_STATUSES: Set<Int> =
                linkedSetOf(
                    SC_REQUEST_TIMEOUT,
                    SC_TOO_MANY_REQUESTS,
                    SC_INTERNAL_ERROR,
                    SC_BAD_GATEWAY,
                    SC_UNAVAILABLE,
                    SC_GATEWAY_TIMEOUT,
                )

            /**
             * Default retryable HTTP methods: idempotent methods per RFC 9110 §9.2.2.
             * POST/PATCH are intentionally excluded — they only retry when the request body
             * is replayable (the orthogonal axis checked by [RetryStep.canRetry]).
             */
            @JvmField
            public val DEFAULT_RETRYABLE_METHODS: Set<Method> =
                linkedSetOf(Method.GET, Method.HEAD, Method.OPTIONS, Method.PUT, Method.DELETE)

            // Spelled-out status constants to satisfy detekt's MagicNumber rule.
            private const val SC_REQUEST_TIMEOUT = 408
            private const val SC_TOO_MANY_REQUESTS = 429
            private const val SC_INTERNAL_ERROR = 500
            private const val SC_BAD_GATEWAY = 502
            private const val SC_UNAVAILABLE = 503
            private const val SC_GATEWAY_TIMEOUT = 504

            /** Java-friendly entry point: returns a fresh builder loaded with defaults. */
            @JvmStatic
            public fun builder(): RetrySettingsBuilder = RetrySettingsBuilder()

            /** Returns an instance with every value at its default. */
            @JvmStatic
            public fun defaults(): RetrySettings = RetrySettingsBuilder().build()
        }
    }
