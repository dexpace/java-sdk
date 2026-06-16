/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import java.util.Locale

/**
 * Composable [HttpRetryConditionPredicate] that lets the server steer the retry decision via an
 * explicit response header (`X-Should-Retry` by default).
 *
 * Some APIs annotate responses with a definitive "retry / do not retry" signal that the client
 * cannot infer from the status code alone — for example a `409 Conflict` that is genuinely
 * transient, or a `503` the server knows will not recover. This predicate honours that signal
 * when, and only when, the header is present:
 *
 *  - A **truthy** value (`true`, `1`, `yes`, `retry`, case-insensitive) forces a retry, even for
 *    a status the default classifier would not retry.
 *  - A **falsy** value (`false`, `0`, `no`, `stop`, case-insensitive) suppresses a retry, even
 *    for a status the default classifier would retry.
 *  - When the header is **absent**, unrecognised, or there is no response (the exception path),
 *    the decision is delegated to [delegate] — so wiring this predicate in changes behaviour
 *    only when the server actually speaks.
 *
 * ## What the override does and does not bypass
 *
 * The override flips only the *classification* decision — "is this response retryable?". It does
 * not bypass the other gates the retry step enforces: a forced retry is still capped by
 * [HttpRetryOptions.maxRetries], and still requires a retry-safe request (an idempotent method or
 * a replayable body). A truthy header on a `POST` carrying a non-replayable body therefore does
 * **not** trigger a retry — the body cannot be re-sent, so the response is returned as-is.
 *
 * ## Opt-in
 *
 * This predicate is **not** installed by default. A caller enables it by passing an instance as
 * [HttpRetryOptions.shouldRetryCondition] (and/or [HttpRetryOptions.shouldRetryException]). The
 * SDK's default retryable-status set is unchanged — in particular `409 Conflict` stays out of
 * it; this predicate is the mechanism by which a server can opt a `409` (or any other status)
 * into a retry, rather than widening the default set for everyone.
 *
 * ## Composition
 *
 * [delegate] defaults to the SDK's standard classifier, dispatched per path: the response
 * classifier (`408 / 429 / 5xx`, except `501` / `505`) on the response path, and the exception
 * classifier (`IOException` / `TimeoutException` anywhere in the cause chain) on the exception
 * path. Because the override header only appears on responses, wiring this predicate into
 * [HttpRetryOptions.shouldRetryException] leaves the exception-path decision entirely to the
 * delegate. Supply a different delegate to layer the server override on top of bespoke retry
 * logic, or [HttpRetryConditionPredicate] `{ false }` to make the server header the sole
 * authority.
 *
 * ## Thread-safety
 *
 * Immutable and stateless — safe to share across concurrent requests.
 *
 * @property headerName The response header consulted for the override signal. Defaults to
 *   `X-Should-Retry`.
 * @property delegate Fallback predicate consulted when the header is absent or unrecognised, or
 *   on the exception path. Defaults to the standard per-path classifier.
 */
public class ServerOverrideRetryPredicate
    @JvmOverloads
    constructor(
        public val headerName: HttpHeaderName = DEFAULT_HEADER_NAME,
        public val delegate: HttpRetryConditionPredicate =
            HttpRetryConditionPredicate(::defaultClassifier),
    ) : HttpRetryConditionPredicate {
        override fun shouldRetry(condition: HttpRetryCondition): Boolean {
            val response = condition.response ?: return delegate.shouldRetry(condition)
            val raw = response.headers.get(headerName) ?: return delegate.shouldRetry(condition)
            return when (raw.trim().lowercase(Locale.US)) {
                in TRUTHY -> true
                in FALSY -> false
                // An unrecognised value is not a directive — defer rather than guess.
                else -> delegate.shouldRetry(condition)
            }
        }

        public companion object {
            /** Default override header (`X-Should-Retry`). */
            @JvmField
            public val DEFAULT_HEADER_NAME: HttpHeaderName = HttpHeaderName.fromString("X-Should-Retry")

            /** Header values that force a retry. */
            private val TRUTHY: Set<String> = setOf("true", "1", "yes", "retry")

            /** Header values that suppress a retry. */
            private val FALSY: Set<String> = setOf("false", "0", "no", "stop")
        }
    }

/**
 * Default [ServerOverrideRetryPredicate.delegate]: applies the SDK's standard classification for
 * whichever path the [condition] represents — the response classifier when a response is present,
 * the exception classifier otherwise. This keeps the override predicate's fall-through behaviour
 * identical to the stock [HttpRetryOptions] defaults on both the response and exception paths.
 */
private fun defaultClassifier(condition: HttpRetryCondition): Boolean =
    if (condition.response != null) {
        defaultShouldRetryResponse(condition)
    } else {
        defaultShouldRetryException(condition)
    }
