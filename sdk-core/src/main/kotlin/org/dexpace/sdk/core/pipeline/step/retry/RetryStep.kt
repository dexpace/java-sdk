/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.NetworkException
import org.dexpace.sdk.core.http.response.exception.Retryable
import org.dexpace.sdk.core.pipeline.ResponseOutcome
import org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Recovery-aware retry step that re-executes a failed request with exponential backoff,
 * server-driven pacing (`Retry-After` / `X-RateLimit-Reset`), and per-attempt deadline
 * shrinking against a total-timeout budget.
 *
 * ## Semantics
 *
 * Retry eligibility is classified off the [Retryable] interface, not concrete exception types:
 *  - On a [ResponseOutcome.Failure] whose throwable is an [HttpException] with `isRetryable =
 *    true` AND whose status is in [RetrySettings.retryableStatuses], the step schedules a
 *    retry — provided the request is idempotency-safe (see [canRetry]).
 *  - On a [ResponseOutcome.Failure] whose throwable is a [NetworkException] (always
 *    [Retryable.isRetryable]), the step always schedules a retry (network-level failures had no
 *    response, so the server never saw the request — replay is safe modulo the body-
 *    replayability check).
 *  - On any other outcome (a non-[Retryable] throwable, a [Retryable] whose flag is `false`, or
 *    a [ResponseOutcome.Success]), the step is a pass-through.
 *
 * ## Idempotency
 *
 * Eligibility is gated on re-sendability, split by whether the request carries a body:
 *  - **No body** — eligible only when `request.method ∈ retryableMethods` (the method is
 *    idempotent). A body-less non-idempotent request (e.g. a bare POST) is not retried.
 *  - **Has a body** — eligible only when `request.body.isReplayable() == true`. A non-replayable
 *    body physically cannot be re-sent: its consume-once guard would trip on the second
 *    `writeTo` and mask the original failure with an [IllegalStateException]. A replayable body
 *    is re-sendable, so the request is retried; ensuring the re-sent request is idempotent (for
 *    a non-idempotent method, e.g. via an idempotency key) is the caller's responsibility.
 *
 * The body-replayability gate closes the hazard where an idempotent method carrying a one-shot
 * body slipped through a method-only check and tripped the consume-once guard on resend. This
 * is a deliberate departure from Square's `RetryInterceptor`, which retries on status alone and
 * silently double-sends a body it cannot replay on transport timeouts.
 *
 * ## Per-attempt header
 *
 * When [RetrySettings.attemptHeaderName] is configured (it is `null` by default), every send is
 * stamped with that header carrying the 1-based attempt ordinal (`1` for the original send, `2`
 * for the first retry, …) on a fresh per-attempt copy of the request, so servers and proxies can
 * observe the retry count. The header is set on a copy via [Request.newBuilder]; the captured
 * template is never mutated, and any idempotency key already on the request is left untouched.
 *
 * ## Cancellation
 *
 * Waits between attempts use [CompletableFuture.get] backed by [scheduler] — never
 * `Thread.sleep`, so virtual-thread carriers can unmount during the wait. On
 * [InterruptedException]:
 *  1. The interrupt flag is restored via `Thread.currentThread().interrupt()`.
 *  2. The pending scheduled future is cancelled.
 *  3. An [InterruptedIOException] is raised through the recovery outcome — matching the SDK's
 *     cancellation contract (see `docs/architecture.md`).
 *
 * ## Lifecycle &amp; thread-safety
 *
 * A `RetryStep` instance is **stateless across calls** and therefore safe to share, reuse,
 * and invoke concurrently — honouring the [ResponseRecoveryStep] contract ("steps are shared
 * across concurrent requests"). The per-call retry state (attempt count, start instant) is
 * not held on the instance; it is allocated fresh inside each top-level [invoke]/[attempt]
 * and threaded through the retry loop as a local [AttemptState]. Two threads invoking the
 * same instance, or the same thread invoking it again after a prior attempt was exhausted,
 * each start from a clean attempt budget. The instance holds no mutable fields at all: the
 * process-wide default scheduler is a companion `by lazy` (SYNCHRONIZED), created at most once
 * VM-wide, so no per-instance guard is needed.
 *
 * The [httpClient] and the originating [request] are captured at construction; a single
 * instance therefore retries exactly that one request template. Construct a distinct
 * `RetryStep` per logical request you intend to retry.
 *
 * Wire it into a pipeline by adding it to the `recoverySteps` of a
 * [org.dexpace.sdk.core.pipeline.ResponsePipeline], or invoke [attempt] directly to obtain
 * the final response without the rest of the pipeline machinery.
 *
 * @property httpClient Transport used to send retry attempts. Captured at construction.
 * @property settings Retry configuration; see [RetrySettings] for defaults.
 * @property request Originating request — used as the template for each retry attempt.
 */
public class RetryStep
    @JvmOverloads
    constructor(
        public val httpClient: HttpClient,
        public val settings: RetrySettings,
        public val request: Request,
        private val clock: Clock = Clock.systemUTC(),
    ) : ResponseRecoveryStep {
        /**
         * Per-call retry bookkeeping. Allocated fresh at the top of each [invoke]/[attempt] so
         * a shared/reused [RetryStep] never inherits a stale attempt budget and concurrent
         * invocations cannot clobber each other's state.
         *
         * @property attempt 1-indexed number for the **next** retry; starts at 1 (the delay
         *  before retry #1) and increments as each retry is dispatched.
         * @property startInstant Wall-clock start of this call's retry involvement, captured
         *  once when the state is created. Drives the total-timeout budget.
         */
        private class AttemptState(
            val startInstant: Instant,
        ) {
            var attempt: Int = 1
        }

        /**
         * Invokes the retry logic on the given [outcome]. On a retryable failure, blocks the
         * current thread for the computed delay (via [ScheduledExecutorService], NOT
         * `Thread.sleep`) and re-issues the request. Returns the final outcome — either a
         * fresh [ResponseOutcome.Success], a terminal [ResponseOutcome.Failure] when retries
         * are exhausted / disallowed, or the input outcome unchanged on the success path.
         */
        override fun invoke(outcome: ResponseOutcome): ResponseOutcome {
            if (outcome is ResponseOutcome.Success) return outcome
            val failure = outcome as ResponseOutcome.Failure
            val error = failure.error

            if (!isClassifiedRetryable(error)) return outcome
            if (!canRetry(request)) return outcome

            return runRetryLoop(error, AttemptState(clock.instant()))
        }

        /**
         * Direct invocation entry — runs the retry loop without the recovery-step plumbing.
         * Sends the original [request] first; on a retryable failure, schedules retries
         * according to [settings] until the budget or attempt cap is exhausted.
         *
         * @return The final [Response] from a successful attempt.
         * @throws Throwable The terminal exception when retries are exhausted or disallowed.
         */
        @Throws(Throwable::class)
        public fun attempt(): Response {
            // The original send is attempt ordinal 1 — stamp it when the feature is enabled so
            // the very first request carries the same observable counter the retries will.
            val initial: ResponseOutcome =
                try {
                    ResponseOutcome.Success(httpClient.execute(stampAttempt(request, attemptOrdinal = 1)))
                } catch (t: Throwable) {
                    ResponseOutcome.Failure(t)
                }
            return when (val final = invoke(initial)) {
                is ResponseOutcome.Success -> final.response
                is ResponseOutcome.Failure -> throw final.error
            }
        }

        /**
         * Drives the retry loop: compute delay, await it, re-execute, repeat until success
         * or exhaustion. Always returns an outcome — exceptions raised during the loop are
         * captured as [ResponseOutcome.Failure].
         *
         * @param state The per-call attempt budget; mutated locally as retries are dispatched.
         */
        private fun runRetryLoop(
            initialError: Throwable,
            state: AttemptState,
        ): ResponseOutcome {
            var lastError: Throwable = initialError
            while (true) {
                when (val readyState = prepareNextAttempt(lastError, state)) {
                    is AttemptStep.Abort -> return ResponseOutcome.Failure(readyState.error)
                    is AttemptStep.Proceed -> {
                        state.attempt += 1
                        // state.attempt is now the 1-based ordinal of the send about to happen:
                        // the original was 1, so the first retry dispatched here is 2.
                        val outcome = executeOnce(state.attempt)
                        if (outcome is ResponseOutcome.Success) return outcome
                        val nextError = (outcome as ResponseOutcome.Failure).error
                        if (!isClassifiedRetryable(nextError)) return outcome
                        lastError = nextError
                    }
                }
            }
        }

        /**
         * Computes the budget/delay state for the next attempt. Returns [AttemptStep.Abort]
         * (with the throwable to surface) when retries are exhausted, the deadline is hit,
         * or the wait is interrupted; returns [AttemptStep.Proceed] otherwise.
         *
         * Centralising every "should we keep retrying?" branch here keeps [runRetryLoop]'s
         * control flow linear and easy to audit; without this split the loop has six exit
         * points and trips detekt's `ReturnCount` rule.
         */
        @Suppress("ReturnCount")
        private fun prepareNextAttempt(
            lastError: Throwable,
            state: AttemptState,
        ): AttemptStep {
            val attempt = state.attempt
            if (attempt >= settings.maxAttempts) return AttemptStep.Abort(lastError)
            val elapsed = Duration.between(state.startInstant, clock.instant())
            if (deadlineExhausted(elapsed)) return AttemptStep.Abort(lastError)
            val hint = retryAfterHintFor(lastError)
            val delay = BackoffCalculator.computeDelay(attempt, settings, hint, elapsed)
            if (!fitsInDeadline(elapsed, delay)) return AttemptStep.Abort(lastError)
            return try {
                awaitDelay(delay)
                AttemptStep.Proceed
            } catch (e: InterruptedIOException) {
                AttemptStep.Abort(e)
            }
        }

        /**
         * Returns true when the total-timeout budget is exhausted relative to [elapsed]. A
         * zero [RetrySettings.totalTimeout] means "unbounded" and never reports exhaustion.
         */
        private fun deadlineExhausted(elapsed: Duration): Boolean {
            if (settings.totalTimeout.isZero) return false
            return !elapsed.isNegative && elapsed >= settings.totalTimeout
        }

        /**
         * Outcome of the budget check before each retry attempt.
         */
        private sealed class AttemptStep {
            /** The deadline is fine, the wait completed; the caller should re-execute. */
            data object Proceed : AttemptStep()

            /** The caller should give up; [error] is the throwable to surface. */
            class Abort(val error: Throwable) : AttemptStep()
        }

        /**
         * Returns the parsed `Retry-After` / `X-RateLimit-Reset` hint from an [HttpException],
         * or `null` when the error is not an [HttpException] or carries no usable hint.
         *
         * [RetryAfterParser] is total and should never throw, but this call is defensively
         * wrapped: any [RuntimeException] from hint parsing is swallowed and the method
         * returns `null`, so the loop falls back to its exponential schedule while keeping the
         * original upstream throwable as the surfaced error. A malformed pacing header must
         * never mask the real upstream failure.
         */
        private fun retryAfterHintFor(error: Throwable): Duration? {
            val http = error as? HttpException ?: return null
            return try {
                RetryAfterParser.parse(http.headers, clock.instant())
            } catch (_: RuntimeException) {
                null
            }
        }

        /**
         * Returns true when [elapsed] + [delay] does not exceed [RetrySettings.totalTimeout].
         * A zero timeout disables the deadline (consistent with `Duration.ZERO` meaning
         * "unbounded" in our setting builder).
         */
        private fun fitsInDeadline(
            elapsed: Duration,
            delay: Duration,
        ): Boolean {
            if (settings.totalTimeout.isZero) return true
            val total = elapsed.plus(delay)
            return total <= settings.totalTimeout
        }

        /**
         * Executes the request once via the captured transport, converting any throwable
         * raised by the transport into a [ResponseOutcome.Failure]. Preserves the interrupt
         * flag if the transport raises an [InterruptedException] mid-send.
         *
         * @param attemptOrdinal The 1-based ordinal of this send, stamped onto the per-attempt
         *  request copy when [RetrySettings.attemptHeaderName] is configured.
         */
        private fun executeOnce(attemptOrdinal: Int): ResponseOutcome =
            try {
                ResponseOutcome.Success(httpClient.execute(stampAttempt(request, attemptOrdinal)))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ResponseOutcome.Failure(e)
            } catch (t: Throwable) {
                ResponseOutcome.Failure(t)
            }

        /**
         * Returns a per-attempt copy of [request] carrying the configured
         * [RetrySettings.attemptHeaderName] set to [attemptOrdinal]. When no attempt header is
         * configured the original [request] is returned unchanged — the no-op path allocates
         * nothing, so the common (feature-off) case pays no cost. The returned copy is built via
         * [Request.newBuilder] so the immutable template the step captured is never mutated; any
         * idempotency key the caller stamped is preserved verbatim because only this single
         * header is replaced.
         */
        private fun stampAttempt(
            request: Request,
            attemptOrdinal: Int,
        ): Request {
            val header = settings.attemptHeaderName ?: return request
            return request.newBuilder()
                .setHeader(header.caseSensitiveName, attemptOrdinal.toString())
                .build()
        }

        /**
         * Blocks the calling thread for [delay] without pinning a carrier thread under Loom.
         * Uses [ScheduledExecutorService.schedule] + [CompletableFuture.get] so the wait can
         * be interrupted and cancelled.
         *
         * @throws InterruptedIOException if interrupted while waiting; the interrupt flag is
         *  restored on the current thread before the throw.
         */
        @Throws(InterruptedIOException::class)
        private fun awaitDelay(delay: Duration) {
            if (delay.isZero || delay.isNegative) return
            val nanos = delay.toNanos()
            val sched = resolveScheduler()
            val future = CompletableFuture<Unit>()
            val scheduled =
                sched.schedule(
                    {
                        future.complete(Unit)
                    },
                    nanos,
                    TimeUnit.NANOSECONDS,
                )
            try {
                future.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                scheduled.cancel(false)
                val ioe = InterruptedIOException("Interrupted while waiting to retry")
                ioe.initCause(e)
                throw ioe
            } catch (e: ExecutionException) {
                // The scheduled action only completes the future — it cannot raise an
                // ExecutionException. If something exotic propagates here, surface it as an
                // interrupted-IO so the caller's catch sites still terminate the retry chain
                // cleanly.
                scheduled.cancel(false)
                val ioe = InterruptedIOException("Retry scheduler raised: ${e.cause?.message ?: e.message}")
                ioe.initCause(e.cause ?: e)
                throw ioe
            }
        }

        /**
         * Returns the [settings]-supplied scheduler, or the process-wide daemon scheduler. The
         * process-wide scheduler is a companion `by lazy` (SYNCHRONIZED), so it is initialised
         * at most once across the whole VM — no per-instance guard is involved.
         */
        private fun resolveScheduler(): ScheduledExecutorService {
            settings.scheduler?.let { return it }
            return DEFAULT_SCHEDULER
        }

        /**
         * Returns true when [error] is an SDK-classified retryable condition. Classification
         * keys off the [Retryable] interface rather than concrete exception types: a non-
         * [Retryable] throwable is never retried, and a [Retryable] is retried only when its
         * [Retryable.isRetryable] flag is set. An [HttpException] carries an additional gate —
         * its status must also be in [RetrySettings.retryableStatuses] — so the caller can
         * narrow which retryable statuses this step actually re-issues. A [NetworkException]
         * (no response, hence no status) passes once its flag is set.
         */
        private fun isClassifiedRetryable(error: Throwable): Boolean {
            if (error !is Retryable || !error.isRetryable) return false
            return error !is HttpException || settings.retryableStatuses.contains(error.status.code)
        }

        /**
         * Returns true when [request] is safe to retry. A body-less request is retry-safe only
         * when its method is in [RetrySettings.retryableMethods] (idempotent) — the gate keys off
         * method idempotency, not off the absence of a body, so a body-less non-idempotent request
         * (a bare `POST`) is NOT retried even though there is no payload to re-send. A body-bearing
         * request is retry-safe only when its body is replayable — a non-replayable body cannot be
         * re-sent (the second `writeTo` trips the consume-once guard). Ensuring a re-sent
         * body-bearing request is idempotent is the caller's responsibility.
         */
        private fun canRetry(request: Request): Boolean {
            val body = request.body ?: return settings.retryableMethods.contains(request.method)
            return body.isReplayable()
        }

        public companion object {
            /**
             * Process-wide single-thread daemon scheduler used when no explicit
             * [ScheduledExecutorService] is supplied via [RetrySettings.scheduler].
             *
             * Daemon-thread so it does not block JVM shutdown. A single thread is sufficient:
             * scheduled callbacks just `future.complete(Unit)` — they do no real work, so
             * one thread can drive an arbitrary number of concurrent retry waits.
             */
            private val DEFAULT_SCHEDULER: ScheduledExecutorService by lazy {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "dexpace-retry-scheduler").apply { isDaemon = true }
                }
            }
        }
    }
