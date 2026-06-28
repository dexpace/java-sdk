/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.pipeline.step.retry.BackoffCalculator
import org.dexpace.sdk.core.pipeline.step.retry.RetryAfterParser
import org.dexpace.sdk.core.pipeline.step.retry.RetrySettings
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.Futures
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * lock-guarded re-arm flag instead of calling [drive] recursively, so a retry sequence of length
 * N never builds an N-deep stack frame chain or an N-deep future continuation graph. The re-arm
 * state is guarded by a lock because a non-blocking backoff hands the next [drive] to a scheduler
 * thread while the previous pump ran on another thread — the lock supplies the happens-before
 * edge that keeps the handoff visible and the exit-check atomic. This mirrors the iterative
 * `while` loop of [DefaultRetryStep] while staying fully async.
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
 * call exceptionally without retry. An [InterruptedIOException] / [InterruptedException] is
 * treated as cancellation, not a retryable failure: the interrupt flag is restored and the call
 * completes with an [InterruptedIOException], matching [DefaultRetryStep]. On terminal failure
 * every prior attempt's exception is attached to the surfaced exception via
 * [Throwable.addSuppressed] — skipping the surfaced exception itself, so a transport that reuses
 * one exception instance across attempts cannot trip self-suppression.
 *
 * A `shouldRetry` predicate or delay computation that throws — or a synchronous scheduler
 * rejection — completes the call exceptionally rather than leaving it hanging, and any open
 * retryable response is closed first so it never leaks (mirroring [DefaultRetryStep]'s
 * close-on-throw guard). Unlike the synchronous stack the terminal failure is surfaced as-is
 * rather than wrapped in [IOException]: a [CompletableFuture] carries no checked-exception
 * contract, so the original failure type reaches the caller (after [Futures.unwrap]) unchanged.
 *
 * ## Subclassing
 *
 * Open specifically to customise delay resolution, matching the synchronous [DefaultRetryStep].
 * Override [computeResponseDelay], [computeExceptionDelay], or [retryAfterFromHeaders] to apply
 * request-specific pacing; an override must return a non-negative [Duration] and must not throw.
 * The retry-decision policy itself stays configured through [HttpRetryOptions].
 *
 * ## Thread-safety
 *
 * Stateless after construction (the per-call [RetryDriver] holds all mutable loop state). The
 * immutable [options] / [clock] / [scheduler] and the [ClientLogger] are shared across
 * concurrent calls. Any subclass override of the delay hooks must likewise be safe for
 * concurrent invocation.
 */
public open class DefaultAsyncRetryStep
    @JvmOverloads
    constructor(
        private val scheduler: ScheduledExecutorService,
        options: HttpRetryOptions = HttpRetryOptions(),
        private val clock: Clock = Clock.SYSTEM,
        internal val logger: ClientLogger = ClientLogger(DefaultAsyncRetryStep::class),
    ) : AsyncRetryStep() {
        /**
         * The stateless retry policy shared with [DefaultRetryStep]: the clamped [HttpRetryOptions]
         * (`maxRetries < 0` → [DefaultRetryStep.DEFAULT_MAX_RETRIES]), the [RetrySettings] backoff
         * view, and the re-sendability / predicate / delay helpers both stacks share. Built once;
         * immutable after construction, so it is safe to share across this step's concurrent calls.
         */
        private val support = RetryPolicySupport(options, logger)

        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> {
            val result = CompletableFuture<Response>()
            val driver = RetryDriver(next, support.isRetrySafe(request), result)
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

            // Trampoline state, guarded by [trampolineLock]. `pumping` is true while a pump loop
            // in drive() is active; `rearm` records that another attempt should run. A re-arm
            // while the pump is active (an inline / zero-delay retry, or a concurrent re-entry)
            // just sets `rearm` and lets the active loop pick it up — it never recurses into a new
            // drive() frame. A re-arm after the pump has exited starts a fresh, shallow pump.
            //
            // The flags are NOT plain fields: a non-blocking backoff hands the next drive() to a
            // ScheduledExecutorService thread while the previous pump ran on the dispatching (or a
            // transport) thread, so the two touch this state from different threads with no
            // intrinsic happens-before edge. Guarding every read/write under the lock both makes
            // the pump-exit write visible to the next cross-thread drive() (no stranding on a
            // stale `pumping`) and makes the exit-check atomic with a concurrent re-arm (no lost
            // wakeup). The lock is held only for the flag flips, never across startAttempt() or
            // the downstream call, so it cannot pin a thread. Per CLAUDE.md, ReentrantLock (not
            // synchronized) so a virtual-thread carrier is never pinned.
            private val trampolineLock = ReentrantLock()
            private var pumping: Boolean = false
            private var rearm: Boolean = false

            /**
             * Entry point and trampoline. Marks that an attempt should run ([rearm]); if a pump
             * loop is already active it returns immediately (the active loop picks up the re-arm
             * on its next exit-check), otherwise it runs the loop. The loop keeps starting attempts
             * as long as completions keep setting [rearm], so a burst of zero-delay retries unwinds
             * iteratively instead of recursing. Safe to call from any thread (see the field note).
             */
            fun drive() {
                trampolineLock.withLock {
                    rearm = true
                    // A pump is already active (this thread or another); it will observe the
                    // re-arm on its next exit-check. Exactly one pump runs at a time.
                    if (pumping) return
                    pumping = true
                }
                while (true) {
                    trampolineLock.withLock {
                        // Atomic exit-check: clearing `pumping` under the same lock as drive()'s
                        // re-arm means a concurrent drive() either set `rearm` before this check
                        // (so the pump continues) or starts a fresh pump after it (because it sees
                        // `pumping` cleared) — the wakeup is never lost.
                        if (!rearm) {
                            pumping = false
                            return
                        }
                        rearm = false
                    }
                    startAttempt()
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
                val delay: Duration =
                    try {
                        val retry =
                            retrySafe &&
                                tryCount < support.options.maxRetries &&
                                shouldRetryResponse(response)
                        if (!retry) {
                            // Not retrying: hand the still-open response to the caller, who then
                            // owns closing it. If the caller already completed or cancelled the
                            // returned future (a race against an in-flight attempt), complete() is
                            // a no-op and the response would otherwise leak its socket/buffer —
                            // close it here in that case.
                            if (!result.complete(response)) closeQuietly(response)
                            return
                        }
                        val computed =
                            this@DefaultAsyncRetryStep.computeResponseDelay(
                                HttpRetryCondition(response, null, tryCount, suppressed ?: emptyList()),
                            )
                        logRetry(tryCount, computed, response.status.code, cause = null)
                        computed
                    } catch (t: Throwable) {
                        // The retry decision, delay computation, or log call threw — e.g. a
                        // misbehaving shouldRetry predicate (surfaced as IllegalStateException), an
                        // Error rethrown by a delay override, or an unexpected backoff failure. The
                        // retryable response is still open, so close it before surfacing —
                        // otherwise its socket/buffer leaks — then complete the call terminally so
                        // the caller's future is never left hanging (a throw escaping this
                        // whenComplete callback would be swallowed, stranding `result`). Mirrors
                        // DefaultRetryStep.decideRetryResponse's close-on-throw guard.
                        closeQuietly(response)
                        failTerminally(t)
                        return
                    }
                // Committed to retrying: release the retryable response before the backoff window,
                // then schedule the next attempt.
                closeQuietly(response)
                tryCount++
                scheduleNext(delay)
            }

            private fun onFailure(rawError: Throwable) {
                val error = Futures.unwrap(rawError)
                // Errors (OOM, StackOverflow, …) are unrecoverable — never retry, never log.
                // Surface as-is, matching DefaultRetryStep (which rethrows an Error before
                // attaching the failure trail).
                if (error is Error) {
                    result.completeExceptionally(error)
                    return
                }
                val exception = error as Exception
                // Interrupts are never retryable, per the SDK-wide cancellation convention.
                // Restore the interrupt flag (the completing thread's catch may have cleared it),
                // normalise a bare InterruptedException to InterruptedIOException, and surface
                // terminally with the prior-attempt trail attached — mirroring DefaultRetryStep's
                // pre-classification interrupt carve-out.
                if (exception is InterruptedIOException || exception is InterruptedException) {
                    Thread.currentThread().interrupt()
                    failTerminally(support.asInterruptedIo(exception))
                    return
                }
                val delay: Duration =
                    try {
                        val retry =
                            retrySafe &&
                                tryCount < support.options.maxRetries &&
                                shouldRetryException(exception)
                        if (!retry) {
                            failTerminally(exception)
                            return
                        }
                        val computed =
                            this@DefaultAsyncRetryStep.computeExceptionDelay(
                                HttpRetryCondition(null, exception, tryCount, suppressed ?: emptyList()),
                            )
                        logRetry(tryCount, computed, statusCode = -1, cause = exception)
                        computed
                    } catch (t: Throwable) {
                        // The retry decision, delay computation, or log call threw. Attach the
                        // in-flight failure and complete terminally so the caller never hangs (a
                        // throw escaping this whenComplete callback would be swallowed). No open
                        // response to close on the exception path.
                        if (t !== exception) t.addSuppressed(exception)
                        failTerminally(t)
                        return
                    }
                // Record the current failure BEFORE scheduling so it is attached to any later
                // terminal exception's suppressed list rather than being silently dropped.
                val accumulator = suppressed ?: ArrayList<Throwable>().also { suppressed = it }
                accumulator.add(exception)
                tryCount++
                scheduleNext(delay)
            }

            /**
             * Completes [result] exceptionally with [error]. Any accumulated prior-attempt
             * failures are attached as suppressed, skipping [error] itself so a transport that
             * re-throws one exception instance across attempts cannot trip
             * [Throwable.addSuppressed]'s self-suppression guard. Used on every terminal path so
             * the caller's future is always completed — never left hanging — with the full failure
             * trail preserved.
             */
            private fun failTerminally(error: Throwable) {
                suppressed?.forEach { prior -> if (prior !== error) error.addSuppressed(prior) }
                result.completeExceptionally(error)
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
                val scheduled =
                    try {
                        Futures.delay(scheduler, safeDelay)
                    } catch (t: Throwable) {
                        // scheduler.schedule rejected the delay task synchronously (e.g. the
                        // scheduler was shut down → RejectedExecutionException). Surface terminally
                        // so the caller never hangs.
                        failTerminally(t)
                        return
                    }
                scheduled.whenComplete { _, scheduleError ->
                    if (scheduleError != null) {
                        // The scheduled delay task failed — surface it with any accumulated prior
                        // failures attached.
                        failTerminally(scheduleError)
                    } else {
                        drive()
                    }
                }
            }

            // --------------- Classification ---------------

            private fun shouldRetryResponse(response: Response): Boolean {
                val condition = HttpRetryCondition(response, null, tryCount, (suppressed ?: emptyList()))
                return support.invokeShouldRetry(support.options.shouldRetryCondition, condition)
            }

            private fun shouldRetryException(exception: Exception): Boolean {
                val condition = HttpRetryCondition(null, exception, tryCount, (suppressed ?: emptyList()))
                return support.invokeShouldRetry(support.options.shouldRetryException, condition)
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

        // --------------- Delay computation (subclass extension points) ---------------

        /**
         * Computes the delay before retrying [condition]'s response. Resolution order mirrors
         * [DefaultRetryStep.computeResponseDelay]:
         *  1. [HttpRetryOptions.delayFromCondition] override (if it returns non-null).
         *  2. `Retry-After` header parsing ([retryAfterFromHeaders]).
         *  3. [HttpRetryOptions.fixedDelay] or exponential backoff.
         *
         * `protected open` so a subclass can apply request-specific delay logic, exactly as the
         * synchronous [DefaultRetryStep] allows. An override MUST return a non-negative [Duration]
         * and MUST NOT throw: a throw aborts the call (the open retryable response is closed first
         * by the loop's close-on-throw guard).
         */
        protected open fun computeResponseDelay(condition: HttpRetryCondition): Duration {
            support.invokeDelayFromCondition(condition)?.let { return it }
            condition.response?.let { retryAfterFromHeaders(it) }?.let { return it }
            return support.backoffOrFixed(condition.tryCount)
        }

        /**
         * Computes the delay before retrying [condition]'s exception. Like [computeResponseDelay]
         * but skips header parsing (there is no response to read headers from). `protected open`
         * with the same invariants. Mirrors [DefaultRetryStep.computeExceptionDelay].
         */
        protected open fun computeExceptionDelay(condition: HttpRetryCondition): Duration {
            support.invokeDelayFromCondition(condition)?.let { return it }
            return support.backoffOrFixed(condition.tryCount)
        }

        /**
         * Walks [HttpRetryOptions.retryAfterHeaders] in order, returning the first parseable delay.
         * `protected open` so a subclass can support additional server-specific pacing headers,
         * mirroring [DefaultRetryStep.retryAfterFromHeaders]. May return `null` to fall through to
         * the default backoff; must not throw.
         */
        protected open fun retryAfterFromHeaders(response: Response): Duration? {
            val now = clock.now()
            for (name in support.options.retryAfterHeaders) {
                val raw = response.headers.get(name) ?: continue
                RetryAfterParser.parseHeaderValue(name, raw, now)?.let { return it }
            }
            return null
        }

        private fun closeQuietly(response: Response) {
            try {
                response.close()
            } catch (closeErr: Exception) {
                // Swallow ANY close failure (not just IOException) on a response being discarded
                // before a retry: it is not actionable, and on the async path an escaping throw
                // would be swallowed by the whenComplete callback and strand the returned future.
                // Only [Error] (OOM, StackOverflow) propagates — those are JVM-fatal, not ours to
                // recover. (The sync DefaultRetryStep lets a non-IOException close failure surface
                // as a terminal error; async cannot, since that path has no caller to throw to.)
                logger.atVerbose()
                    .event("http.retry.close_failed")
                    .field("error.type", closeErr::class.java.simpleName ?: "Exception")
                    .log()
            }
        }

        public companion object {
            // Nanoseconds in one millisecond — converts monotonic deltas to ms for log events.
            private const val NANOS_PER_MILLI = 1_000_000L
        }
    }
