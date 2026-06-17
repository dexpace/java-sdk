/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.pipeline.step.retry.BackoffCalculator
import org.dexpace.sdk.core.pipeline.step.retry.RetryAfterParser
import org.dexpace.sdk.core.pipeline.step.retry.RetrySettings
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.Futures
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

/**
 * Default [AsyncRetryStep] — the async mirror of [DefaultRetryStep]. Re-invokes the downstream
 * async chain on classified failures with exponential / fixed backoff, server-supplied
 * [HttpHeaderName.RETRY_AFTER] pacing, and idempotency-aware re-sendability gating, sharing the
 * exact same policy as the synchronous stack: the same [HttpRetryOptions], the same
 * [BackoffCalculator], the same [RetryAfterParser], the same `Retry-After` header set, and the
 * same idempotent-method allow-list.
 *
 * ## Non-blocking delays
 *
 * Backoff delays are scheduled on a [ScheduledExecutorService] via [Futures.delay] — no
 * `Thread.sleep`, no `Timer`. While a delay is pending the dispatching thread is free; the loop
 * re-arms when the scheduled future fires.
 *
 * ## Loop shape — stack-safe, no `thenCompose` recursion
 *
 * The retry loop is driven iteratively by [drive], not by chaining `thenCompose` per attempt.
 * Each downstream attempt registers a single [CompletableFuture.whenComplete] callback. When the
 * outcome warrants another attempt, the callback hands control back to [drive] through a
 * trampoline ([RetryDriver.continuation]) instead of calling [drive] recursively, so a retry
 * sequence of length N never builds an N-deep stack frame chain or an N-deep future
 * continuation graph. This mirrors the iterative `while` loop of [DefaultRetryStep] while staying
 * fully async.
 *
 * ## Re-sendability gating
 *
 * Identical to [DefaultRetryStep]:
 *  - **No body** — retried only when the method is idempotent ([IDEMPOTENT_METHODS]); a bare
 *    non-idempotent `POST` is not retried even though there is nothing to re-send.
 *  - **Has a body** — retried only when [org.dexpace.sdk.core.http.request.RequestBody.isReplayable].
 *
 * When the request is not re-sendable the loop runs exactly one attempt and completes with the
 * response (or the failure) as-is.
 *
 * ## Failure handling
 *
 * Downstream failures surface as exceptionally-completed futures; the loop unwraps the
 * [java.util.concurrent.CompletionException] wrapper via [Futures.unwrap] before classifying.
 * Only [Exception] subclasses are classified — an [Error] (OOM, StackOverflow) completes the
 * call exceptionally without retry. On terminal failure every prior attempt's exception is
 * attached to the surfaced exception via [Throwable.addSuppressed].
 *
 * ## Thread-safety
 *
 * Stateless after construction (the per-call [RetryDriver] holds all mutable loop state). The
 * immutable [options] / [clock] / [scheduler] and the [ClientLogger] are shared across
 * concurrent calls.
 */
public open class DefaultAsyncRetryStep
    @JvmOverloads
    constructor(
        private val scheduler: ScheduledExecutorService,
        options: HttpRetryOptions = HttpRetryOptions(),
        private val clock: Clock = Clock.SYSTEM,
        internal val logger: ClientLogger = ClientLogger(DefaultAsyncRetryStep::class),
    ) : AsyncRetryStep() {
        /** Effective options. `maxRetries < 0` is clamped to [DefaultRetryStep.DEFAULT_MAX_RETRIES]. */
        private val options: HttpRetryOptions = clampOptions(options)

        /**
         * The [options]' exponential parameters as a [RetrySettings] view so the shared
         * [BackoffCalculator] computes this stack's schedule — built once, exactly as
         * [DefaultRetryStep.backoffSettings]. `totalTimeout = ZERO` disables the deadline cap.
         * Building it eagerly validates the delay magnitudes at construction.
         */
        private val backoffSettings: RetrySettings =
            RetrySettings.builder()
                .initialDelay(this.options.baseDelay)
                .maxDelay(this.options.maxDelay)
                .delayMultiplier(RetrySettings.DEFAULT_DELAY_MULTIPLIER)
                .jitter(RetrySettings.DEFAULT_JITTER)
                .totalTimeout(Duration.ZERO)
                .build()

        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> {
            val result = CompletableFuture<Response>()
            val driver = RetryDriver(next, isRetrySafe(request), result)
            driver.drive()
            return result
        }

        /**
         * Per-call mutable loop state plus the trampolining driver. One instance per
         * [processAsync] call; never shared across calls or threads (each call's continuations
         * run sequentially on the scheduler / completing thread, never concurrently with
         * themselves).
         */
        private inner class RetryDriver(
            private val next: AsyncPipelineNext,
            private val retrySafe: Boolean,
            private val result: CompletableFuture<Response>,
        ) {
            private var tryCount = 0
            private val sequenceStartNanos = clock.monotonic()

            // Lazily allocated on first failure so the success path never pays for the list.
            private var suppressed: MutableList<Throwable>? = null

            // Trampoline state. `pumping` is true while the synchronous pump loop in drive() is
            // active; `rearm` records that another attempt should run. A re-arm that happens
            // while the pump is active (an inline / zero-delay retry) just sets `rearm` and lets
            // the loop pick it up — it never recurses into a new drive() frame. A re-arm that
            // happens after the pump has exited (the common async case, fired from a scheduler
            // or downstream-completion thread) starts a fresh, shallow pump. Both paths run
            // sequentially per call — drive() is only ever entered by one thread at a time
            // because each attempt's continuation fires exactly once and the previous pump has
            // returned before the async callback runs.
            private var pumping: Boolean = false
            private var rearm: Boolean = false

            /**
             * Entry point and trampoline. Marks that an attempt should run ([rearm]); if a pump
             * loop is already active it returns immediately (the active loop will pick up the
             * re-arm), otherwise it runs the loop. The loop keeps starting attempts as long as
             * inline completions keep setting [rearm], so a burst of zero-delay retries unwinds
             * iteratively instead of recursing.
             */
            fun drive() {
                rearm = true
                if (pumping) return
                pumping = true
                try {
                    while (rearm) {
                        rearm = false
                        startAttempt()
                    }
                } finally {
                    pumping = false
                }
            }

            /**
             * Launches one downstream attempt and registers its completion handler. When the
             * attempt (and its retry decision + zero-length delay) complete inline, the handler
             * runs synchronously and re-enters [drive] — which, because [pumping] is still true,
             * merely sets [rearm] for the active loop. When the attempt completes later, the
             * handler runs on the completing thread and starts a fresh pump.
             */
            private fun startAttempt() {
                val attempt: CompletableFuture<Response> =
                    try {
                        next.copy().processAsync()
                    } catch (e: Exception) {
                        // The async chain contract permits sync exceptions only for caller-bug
                        // cases; normalise to a failed future so classification is uniform.
                        Futures.failed(e)
                    }
                attempt.whenComplete { response, error -> handleOutcome(response, error) }
            }

            /** Routes a completed attempt to the success or failure handler. */
            private fun handleOutcome(
                response: Response?,
                error: Throwable?,
            ) {
                if (error == null) {
                    onSuccess(response!!)
                } else {
                    onFailure(error)
                }
            }

            private fun onSuccess(response: Response) {
                val retry =
                    retrySafe &&
                        tryCount < options.maxRetries &&
                        shouldRetryResponse(response)
                if (!retry) {
                    result.complete(response)
                    return
                }
                val delay = computeResponseDelay(response, tryCount)
                logRetry(tryCount, delay, response.status.code, cause = null)
                closeQuietly(response)
                tryCount++
                scheduleNext(delay)
            }

            private fun onFailure(rawError: Throwable) {
                val error = Futures.unwrap(rawError)
                // Errors (OOM, StackOverflow, …) are unrecoverable — never retry, never log.
                if (error is Error) {
                    result.completeExceptionally(error)
                    return
                }
                val exception = error as Exception
                val retry =
                    retrySafe &&
                        tryCount < options.maxRetries &&
                        shouldRetryException(exception)
                if (!retry) {
                    suppressed?.forEach(exception::addSuppressed)
                    result.completeExceptionally(exception)
                    return
                }
                val accumulator = suppressed ?: ArrayList<Throwable>().also { suppressed = it }
                val delay = computeExceptionDelay(exception, tryCount)
                logRetry(tryCount, delay, statusCode = -1, cause = exception)
                // Record the current failure BEFORE scheduling so it is attached to any later
                // terminal exception's suppressed list rather than being silently dropped.
                accumulator.add(exception)
                tryCount++
                scheduleNext(delay)
            }

            /**
             * Schedules the next attempt after [delay]. [Futures.delay] returns an
             * already-complete future for a zero delay, so the [CompletableFuture.whenComplete]
             * callback runs inline and re-enters [drive] while the pump loop is still active — a
             * synchronous re-arm with no extra stack frame. For a positive delay the scheduled
             * future fires later on the scheduler thread and starts a fresh pump.
             */
            private fun scheduleNext(delay: Duration) {
                val safeDelay = if (delay.isNegative) Duration.ZERO else delay
                Futures.delay(scheduler, safeDelay).whenComplete { _, scheduleError ->
                    if (scheduleError != null) {
                        // The scheduler rejected or failed the delay task — surface it with any
                        // accumulated prior failures attached.
                        suppressed?.forEach(scheduleError::addSuppressed)
                        result.completeExceptionally(scheduleError)
                    } else {
                        drive()
                    }
                }
            }

            // --------------- Classification ---------------

            private fun shouldRetryResponse(response: Response): Boolean {
                val condition = HttpRetryCondition(response, null, tryCount, (suppressed ?: emptyList()))
                return invokeShouldRetry(options.shouldRetryCondition, condition)
            }

            private fun shouldRetryException(exception: Exception): Boolean {
                val condition = HttpRetryCondition(null, exception, tryCount, (suppressed ?: emptyList()))
                return invokeShouldRetry(options.shouldRetryException, condition)
            }

            // --------------- Delay computation ---------------

            private fun computeResponseDelay(
                response: Response,
                tryCount: Int,
            ): Duration {
                val condition = HttpRetryCondition(response, null, tryCount, (suppressed ?: emptyList()))
                invokeDelayFromCondition(condition)?.let { return it }
                retryAfterFromHeaders(response)?.let { return it }
                return backoffOrFixed(tryCount)
            }

            private fun computeExceptionDelay(
                exception: Exception,
                tryCount: Int,
            ): Duration {
                val condition = HttpRetryCondition(null, exception, tryCount, (suppressed ?: emptyList()))
                invokeDelayFromCondition(condition)?.let { return it }
                return backoffOrFixed(tryCount)
            }

            // --------------- Logging ---------------

            private fun logRetry(
                tryCount: Int,
                delay: Duration,
                statusCode: Int,
                cause: Throwable?,
            ) {
                val event =
                    logger.atInfo()
                        .event("http.retry")
                        .field("http.retry.try_count", tryCount.toLong())
                        .field("http.retry.delay_ms", delay.toMillis())
                        .field("retry.total_elapsed_ms", (clock.monotonic() - sequenceStartNanos) / NANOS_PER_MILLI)
                if (statusCode > 0) {
                    event.field("http.response.status_code", statusCode.toLong())
                }
                if (cause != null) {
                    event.field("error.type", cause::class.java.simpleName ?: "Throwable")
                        .field("retry.cause_class", cause::class.simpleName ?: "Throwable")
                        .cause(cause)
                }
                event.log()
            }
        }

        // --------------- Shared helpers (stateless across calls) ---------------

        private fun isRetrySafe(request: Request): Boolean {
            val body = request.body ?: return request.method in IDEMPOTENT_METHODS
            return body.isReplayable()
        }

        private fun invokeShouldRetry(
            predicate: HttpRetryConditionPredicate,
            condition: HttpRetryCondition,
        ): Boolean =
            try {
                predicate.shouldRetry(condition)
            } catch (t: Throwable) {
                @Suppress("InstanceOfCheckForException")
                if (t is Error) throw t
                throw IllegalStateException("shouldRetry predicate threw", t)
            }

        private fun invokeDelayFromCondition(condition: HttpRetryCondition): Duration? =
            try {
                options.delayFromCondition.delayFor(condition)
            } catch (t: Throwable) {
                @Suppress("InstanceOfCheckForException")
                if (t is Error) throw t
                logger.atWarning()
                    .event("http.retry.delay_override_failed")
                    .field("error.type", t::class.java.simpleName ?: "Throwable")
                    .cause(t)
                    .log()
                null
            }

        private fun backoffOrFixed(tryCount: Int): Duration =
            options.fixedDelay ?: BackoffCalculator.computeDelay(tryCount + 1, backoffSettings)

        private fun retryAfterFromHeaders(response: Response): Duration? {
            val now = clock.now()
            for (name in options.retryAfterHeaders) {
                val raw = response.headers.get(name) ?: continue
                RetryAfterParser.parseHeaderValue(name, raw, now)?.let { return it }
            }
            return null
        }

        private fun closeQuietly(response: Response) {
            try {
                response.close()
            } catch (closeErr: IOException) {
                logger.atVerbose()
                    .event("http.retry.close_failed")
                    .field("error.type", closeErr::class.java.simpleName ?: "IOException")
                    .log()
            }
        }

        private fun clampOptions(opts: HttpRetryOptions): HttpRetryOptions {
            if (opts.maxRetries >= 0) return opts
            logger.atVerbose()
                .event("http.retry.maxRetries_clamped")
                .field("http.retry.max_retries.requested", opts.maxRetries.toLong())
                .field("http.retry.max_retries.applied", DefaultRetryStep.DEFAULT_MAX_RETRIES.toLong())
                .log()
            return HttpRetryOptions(
                maxRetries = DefaultRetryStep.DEFAULT_MAX_RETRIES,
                baseDelay = opts.baseDelay,
                maxDelay = opts.maxDelay,
                fixedDelay = opts.fixedDelay,
                retryAfterHeaders = opts.retryAfterHeaders,
                shouldRetryCondition = opts.shouldRetryCondition,
                shouldRetryException = opts.shouldRetryException,
                delayFromCondition = opts.delayFromCondition,
            )
        }

        public companion object {
            // Nanoseconds in one millisecond — converts monotonic deltas to ms for log events.
            private const val NANOS_PER_MILLI = 1_000_000L

            // Methods safe to re-send regardless of body replayability (idempotent per RFC 9110).
            // Mirrors DefaultRetryStep.IDEMPOTENT_METHODS / RetrySettings.DEFAULT_RETRYABLE_METHODS.
            private val IDEMPOTENT_METHODS: Set<Method> =
                setOf(Method.GET, Method.HEAD, Method.OPTIONS, Method.PUT, Method.DELETE)
        }
    }
