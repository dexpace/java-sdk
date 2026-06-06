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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Recovery-aware retry step that re-executes a failed request with exponential backoff,
 * server-driven pacing (`Retry-After` / `X-RateLimit-Reset`), and per-attempt deadline
 * shrinking against a total-timeout budget.
 *
 * ## Semantics
 *
 *  - On a [ResponseOutcome.Failure] whose throwable is an [HttpException] with `retryable =
 *    true` AND whose status is in [RetrySettings.retryableStatuses], the step schedules a
 *    retry — provided the request is idempotency-safe (see [canRetry]).
 *  - On a [ResponseOutcome.Failure] whose throwable is a [NetworkException], the step always
 *    schedules a retry (network-level failures had no response, so the server never saw the
 *    request — replay is safe modulo the body-replayability check).
 *  - On any other outcome (other throwable types or a [ResponseOutcome.Success]), the step
 *    is a pass-through.
 *
 * ## Idempotency
 *
 * A request is eligible for retry when either:
 *  - `request.method ∈ retryableMethods`, **or**
 *  - `request.body?.isReplayable() == true` (body can be re-sent verbatim).
 *
 * Non-idempotent methods (POST/PATCH) with non-replayable bodies are NOT retried. This is a
 * deliberate departure from Square's `RetryInterceptor`, which retries on status alone and
 * silently double-sends POSTs on transport timeouts.
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
 * ## Lifecycle
 *
 * One `RetryStep` instance is intended to be created **per call** because it carries both
 * the [HttpClient] reference (transport) and the originating [request]. The retry state
 * (attempt count, start instant) is held on the instance itself rather than a thread-local
 * or [org.dexpace.sdk.core.http.context.RequestContext] mutation so the step is safe to
 * reuse across the recovery chain without external synchronisation primitives.
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
        /** 1-indexed attempt number for the **next** retry. Starts at 1 (delay before retry #1). */
        private val attemptNumber: AtomicLong = AtomicLong(1L)

        /** Wall-clock start of this RetryStep's involvement. Captured at first invocation. */
        private val startInstant: Instant = clock.instant()

        /** Lock guards lazy-init of the default scheduler so we never create two. */
        private val lock: ReentrantLock = ReentrantLock()

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

            return runRetryLoop(error)
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
            val initial: ResponseOutcome =
                try {
                    ResponseOutcome.Success(httpClient.execute(request))
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
         */
        private fun runRetryLoop(initialError: Throwable): ResponseOutcome {
            var lastError: Throwable = initialError
            while (true) {
                val readyState = prepareNextAttempt(lastError)
                when (readyState) {
                    is AttemptStep.Abort -> return ResponseOutcome.Failure(readyState.error)
                    is AttemptStep.Proceed -> {
                        attemptNumber.incrementAndGet()
                        val outcome = executeOnce()
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
        private fun prepareNextAttempt(lastError: Throwable): AttemptStep {
            val attempt = attemptNumber.get().toInt()
            if (attempt >= settings.maxAttempts) return AttemptStep.Abort(lastError)
            val elapsed = Duration.between(startInstant, clock.instant())
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
            object Proceed : AttemptStep()

            /** The caller should give up; [error] is the throwable to surface. */
            class Abort(val error: Throwable) : AttemptStep()
        }

        /** Returns the parsed `Retry-After` / `X-RateLimit-Reset` hint from an [HttpException], or null. */
        private fun retryAfterHintFor(error: Throwable): Duration? =
            (error as? HttpException)?.let { RetryAfterParser.parse(it.headers, clock.instant()) }

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
         */
        private fun executeOnce(): ResponseOutcome =
            try {
                ResponseOutcome.Success(httpClient.execute(request))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ResponseOutcome.Failure(e)
            } catch (t: Throwable) {
                ResponseOutcome.Failure(t)
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
         * Returns the [settings]-supplied scheduler, or lazily creates the process-wide
         * daemon scheduler. The lazy init runs at most once per RetryStep instance via the
         * `lock`; the process-wide scheduler itself is initialised at most once across the
         * whole VM via the companion `by lazy`.
         */
        private fun resolveScheduler(): ScheduledExecutorService {
            settings.scheduler?.let { return it }
            return lock.withLock { DEFAULT_SCHEDULER }
        }

        /**
         * Returns true when [error] is an SDK-classified retryable condition AND, for
         * [HttpException], the status is in [RetrySettings.retryableStatuses].
         */
        private fun isClassifiedRetryable(error: Throwable): Boolean =
            when (error) {
                is NetworkException -> error.retryable
                is HttpException -> error.retryable && settings.retryableStatuses.contains(error.status.code)
                else -> false
            }

        /**
         * Returns true when [request] is safe to retry — its method is in
         * [RetrySettings.retryableMethods] OR its body is replayable. A non-idempotent
         * method with a non-replayable body cannot be safely re-sent.
         */
        private fun canRetry(request: Request): Boolean {
            if (settings.retryableMethods.contains(request.method)) return true
            val body = request.body ?: return true
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
