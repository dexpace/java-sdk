/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.pipeline.step.retry.RetrySettings
import org.dexpace.sdk.core.util.RetryUtils
import java.time.Duration
import java.util.Collections

/**
 * Predicate that decides whether a request should be retried based on the HTTP response or
 * exception captured in [HttpRetryCondition].
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 */
public fun interface HttpRetryConditionPredicate {
    /**
     * Returns `true` if the request should be retried for the given [condition].
     */
    public fun shouldRetry(condition: HttpRetryCondition): Boolean
}

/**
 * Provider that computes a custom retry delay from [HttpRetryCondition]. Returning `null`
 * defers to the next delay-resolution step (Retry-After headers, then backoff).
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 */
public fun interface HttpRetryDelayProvider {
    /**
     * Returns a custom delay for [condition], or `null` to fall through to the default
     * delay resolution (Retry-After header parsing, then fixed or exponential backoff).
     */
    public fun delayFor(condition: HttpRetryCondition): Duration?
}

/**
 * Configuration for [DefaultRetryStep]. The numeric defaults are the SDK's canonical retry
 * defaults, shared with the recovery-aware [RetrySettings] so both retry stacks compute the
 * same backoff schedule (via [org.dexpace.sdk.core.pipeline.step.retry.BackoffCalculator]):
 *  - [maxRetries] = 2 (initial send + 2 retries = 3 total attempts, matching
 *    [RetrySettings.DEFAULT_MAX_ATTEMPTS]).
 *  - [baseDelay] = 200ms (= [RetrySettings.DEFAULT_INITIAL_DELAY]; exponentially scaled per attempt).
 *  - [maxDelay] = 8s (= [RetrySettings.DEFAULT_MAX_DELAY]; cap on the scaled delay).
 *  - [fixedDelay] = null (exponential backoff is used; when non-null it overrides the
 *    backoff entirely and every retry waits exactly [fixedDelay]).
 *  - [retryAfterHeaders] = `Retry-After`, `retry-after-ms`, `x-ms-retry-after-ms` —
 *    parsed in declared order; the first present wins. Drop the Microsoft-specific
 *    variants by passing a tighter list for stricter posture.
 *  - [shouldRetryCondition] = retry on 408 / 429 / 5xx (except 501, 505), per [RetryUtils.isRetryable].
 *  - [shouldRetryException] = retry on any [java.io.IOException] / [java.util.concurrent.TimeoutException]
 *    anywhere in the cause chain, per [RetryUtils.isRetryable].
 *  - [delayFromCondition] = null delay (falls through to `Retry-After` parsing, then backoff).
 *
 * The exponential schedule, multiplier (2.0), and symmetric jitter (0.2) are sourced from the
 * shared [RetrySettings] constants and applied by `BackoffCalculator`, so this stack and the
 * recovery-aware stack cannot drift apart. The one intentional difference is the deadline: this
 * stage-based step has no total-timeout budget, so it never shrinks a delay against one.
 *
 * The companion [HttpRetryOptions.fixed] factory builds an options instance whose delay
 * never grows — useful for test injection or high-throughput retry against flaky endpoints.
 */
public class HttpRetryOptions
    @JvmOverloads
    constructor(
        public val maxRetries: Int = DEFAULT_MAX_RETRIES,
        public val baseDelay: Duration = RetrySettings.DEFAULT_INITIAL_DELAY,
        public val maxDelay: Duration = RetrySettings.DEFAULT_MAX_DELAY,
        public val fixedDelay: Duration? = null,
        public val retryAfterHeaders: List<HttpHeaderName> = DEFAULT_RETRY_AFTER_HEADERS,
        public val shouldRetryCondition: HttpRetryConditionPredicate =
            HttpRetryConditionPredicate(::defaultShouldRetryResponse),
        public val shouldRetryException: HttpRetryConditionPredicate =
            HttpRetryConditionPredicate(::defaultShouldRetryException),
        public val delayFromCondition: HttpRetryDelayProvider = HttpRetryDelayProvider { null },
    ) {
        public companion object {
            // The default retry count is the canonical SDK budget, kept in one place on
            // DefaultRetryStep (initial send + DEFAULT_MAX_RETRIES == RetrySettings.DEFAULT_MAX_ATTEMPTS).
            private const val DEFAULT_MAX_RETRIES = DefaultRetryStep.DEFAULT_MAX_RETRIES

            /**
             * The three `Retry-After` header forms parsed by [DefaultRetryStep]. Order matters —
             * the first header present on the response wins.
             */
            @JvmField
            public val DEFAULT_RETRY_AFTER_HEADERS: List<HttpHeaderName> =
                Collections.unmodifiableList(
                    listOf(
                        HttpHeaderName.RETRY_AFTER,
                        HttpHeaderName.RETRY_AFTER_MS,
                        HttpHeaderName.X_MS_RETRY_AFTER_MS,
                    ),
                )

            /**
             * Returns an [HttpRetryOptions] that uses a flat [delay] between every retry — no
             * exponential growth, no jitter. [baseDelay] and [maxDelay] are forced to zero
             * so the backoff path is unreachable.
             */
            @JvmStatic
            public fun fixed(
                maxRetries: Int,
                delay: Duration,
            ): HttpRetryOptions =
                HttpRetryOptions(
                    maxRetries = maxRetries,
                    fixedDelay = delay,
                    baseDelay = Duration.ZERO,
                    maxDelay = Duration.ZERO,
                )
        }
    }

/**
 * Default response-side retry predicate. Retries when the status code is in
 * [RetryUtils.isRetryable]'s allow-list (408, 429, 5xx except 501/505).
 */
internal fun defaultShouldRetryResponse(condition: HttpRetryCondition): Boolean {
    val response = condition.response ?: return false
    return RetryUtils.isRetryable(response.status.code)
}

/**
 * Default exception-side retry predicate. Retries when the throwable (or any cause in its
 * chain) is an [java.io.IOException] or [java.util.concurrent.TimeoutException]. Cycles in
 * the cause chain are detected by [RetryUtils.isRetryable].
 */
internal fun defaultShouldRetryException(condition: HttpRetryCondition): Boolean {
    val ex = condition.exception ?: return false
    return RetryUtils.isRetryable(ex)
}
