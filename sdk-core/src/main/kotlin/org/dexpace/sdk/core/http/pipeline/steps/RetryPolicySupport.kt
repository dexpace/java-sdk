/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.pipeline.step.retry.BackoffCalculator
import org.dexpace.sdk.core.pipeline.step.retry.RetrySettings
import java.io.InterruptedIOException
import java.time.Duration

/**
 * Stateless retry policy shared by [DefaultRetryStep] and [DefaultAsyncRetryStep]. Clamps the
 * caller's [HttpRetryOptions] (a negative `maxRetries` becomes
 * [DefaultRetryStep.DEFAULT_MAX_RETRIES]) and builds the [RetrySettings] backoff view once, then
 * exposes the policy helpers both drivers share: re-sendability gating, interrupt normalisation,
 * predicate invocation, the caller delay override, and fixed-or-exponential backoff.
 *
 * Per-call state (try count, suppressed trail, terminal completion), the `protected open` delay
 * hooks, and the close-on-discard helper stay on each step — those differ between the blocking and
 * async stacks. Holds no mutable state after construction, so it is safe to share across
 * concurrent calls.
 */
internal class RetryPolicySupport(
    rawOptions: HttpRetryOptions,
    private val logger: ClientLogger,
) {
    /** Effective options; `maxRetries < 0` is clamped to [DefaultRetryStep.DEFAULT_MAX_RETRIES]. */
    val options: HttpRetryOptions = clampOptions(rawOptions)

    /**
     * The [options]' exponential parameters as a [RetrySettings] view so the shared
     * [BackoffCalculator] computes this stack's schedule. Built once; `totalTimeout = ZERO`
     * disables the deadline cap. Building it eagerly validates the delay magnitudes.
     */
    val backoffSettings: RetrySettings =
        RetrySettings.builder()
            .initialDelay(options.baseDelay)
            .maxDelay(options.maxDelay)
            .delayMultiplier(RetrySettings.DEFAULT_DELAY_MULTIPLIER)
            .jitter(RetrySettings.DEFAULT_JITTER)
            .totalTimeout(Duration.ZERO)
            .build()

    /**
     * Returns `true` when [request] may be re-sent: a body-less request only when its method is
     * idempotent ([IDEMPOTENT_METHODS]); a body-bearing request only when its body is replayable.
     */
    fun isRetrySafe(request: Request): Boolean {
        val body = request.body ?: return request.method in IDEMPOTENT_METHODS
        return body.isReplayable()
    }

    /**
     * Normalises an interrupt-signalling exception to [InterruptedIOException]: an
     * [InterruptedIOException] is returned as-is; a bare [InterruptedException] is wrapped with the
     * original attached as its cause.
     */
    fun asInterruptedIo(exception: Exception): InterruptedIOException =
        when (exception) {
            is InterruptedIOException -> exception
            else -> InterruptedIOException("retry interrupted").apply { initCause(exception) }
        }

    fun invokeShouldRetry(
        predicate: HttpRetryConditionPredicate,
        condition: HttpRetryCondition,
    ): Boolean =
        try {
            predicate.shouldRetry(condition)
        } catch (t: Throwable) {
            // Error subclasses still rethrown; an OOM in the predicate must not be wrapped.
            // Splitting Error from RuntimeException via `is` is the canonical JVM idiom for
            // retry classification — there is no other way to distinguish JVM Errors here.
            @Suppress("InstanceOfCheckForException")
            if (t is Error) throw t
            throw IllegalStateException("shouldRetry predicate threw", t)
        }

    fun invokeDelayFromCondition(condition: HttpRetryCondition): Duration? =
        try {
            options.delayFromCondition.delayFor(condition)
        } catch (t: Throwable) {
            @Suppress("InstanceOfCheckForException")
            if (t is Error) throw t
            // Don't fail the whole pipeline if the user override misbehaves — fall back to the
            // default delay calculation. Log loud enough that the bug is observable.
            logger.atWarning()
                .event("http.retry.delay_override_failed")
                .field("error.type", t::class.java.simpleName ?: "Throwable")
                .cause(t)
                .log()
            null
        }

    fun backoffOrFixed(tryCount: Int): Duration =
        options.fixedDelay ?: BackoffCalculator.computeDelay(tryCount + 1, backoffSettings)

    private fun clampOptions(opts: HttpRetryOptions): HttpRetryOptions {
        if (opts.maxRetries >= 0) return opts
        logger.atVerbose()
            .event("http.retry.maxRetries_clamped")
            .field("http.retry.max_retries.requested", opts.maxRetries.toLong())
            .field("http.retry.max_retries.applied", DefaultRetryStep.DEFAULT_MAX_RETRIES.toLong())
            .log()
        return opts.withMaxRetries(DefaultRetryStep.DEFAULT_MAX_RETRIES)
    }

    private companion object {
        // Methods safe to re-send regardless of body replayability (idempotent per RFC 9110).
        // Mirrors RetrySettings.DEFAULT_RETRYABLE_METHODS.
        private val IDEMPOTENT_METHODS: Set<Method> =
            setOf(Method.GET, Method.HEAD, Method.OPTIONS, Method.PUT, Method.DELETE)
    }
}
