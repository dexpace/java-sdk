/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.pipeline.step.retry.BackoffCalculator
import org.dexpace.sdk.core.pipeline.step.retry.RetryAfterParser
import org.dexpace.sdk.core.pipeline.step.retry.RetrySettings
import org.dexpace.sdk.core.util.Clock
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration

/**
 * Default [RetryStep]. Drives an iterative retry loop with classified failure detection,
 * exponential / fixed delay scheduling, server-supplied [HttpHeaderName.RETRY_AFTER]
 * pacing, and accumulated suppressed exceptions on the final failure.
 *
 * ## Loop shape
 *
 * The loop is iterative, not recursive — stack-safe for `maxRetries` in the thousands.
 * Each iteration:
 *  1. Calls [PipelineNext.copy] then [PipelineNext.process] for a fresh chain state — never
 *     reuses the original [PipelineNext] across attempts.
 *  2. Classifies the outcome via [HttpRetryOptions.shouldRetryCondition] (responses) or
 *     [HttpRetryOptions.shouldRetryException] (exceptions). Predicates that throw abort
 *     the loop with an [IllegalStateException].
 *  3. Closes a retryable response BEFORE sleeping (the delay is computed first while the body
 *     is still open, then the body's resources are released so the socket/buffer is not pinned
 *     across the backoff window).
 *  4. Sleeps via [Clock.sleep]; an interrupt during sleep throws [InterruptedIOException]
 *     with the original [InterruptedException] and any accumulated prior failures attached
 *     as suppressed — retries are NOT resumed after an interrupt.
 *  5. Computes the exponential delay through the shared [BackoffCalculator], which saturates
 *     rather than overflows on extreme attempt counts and clamps to [HttpRetryOptions.maxDelay].
 *
 * ## Body replayability
 *
 * Eligibility is gated on re-sendability. A body-less request is retried only when its method
 * is idempotent; a body-bearing request is retried only when its body is replayable — a
 * non-replayable body physically cannot be re-sent (the second `writeTo` trips the body's
 * consume-once guard and surfaces as a confusing wrapped [IllegalStateException] that masks the
 * real failure). A replayable body is re-sendable regardless of method; making the re-sent
 * request idempotent (for a non-idempotent method, e.g. via an idempotency key) is the caller's
 * responsibility. When the request is not re-sendable the loop runs exactly one attempt and
 * returns the response (or rethrows the exception) as-is. This mirrors
 * `pipeline.step.retry.RetryStep.canRetry`.
 *
 * ## Delay precedence (highest to lowest)
 *
 *  1. [HttpRetryOptions.delayFromCondition] — caller-supplied override; if it returns
 *     non-null, that delay is used verbatim.
 *  2. [HttpHeaderName.RETRY_AFTER] / [HttpHeaderName.RETRY_AFTER_MS] / [HttpHeaderName.X_MS_RETRY_AFTER_MS]
 *     parsed from the response (response path only). A negative or unparseable value
 *     falls through; a value of zero produces an immediate retry.
 *  3. [HttpRetryOptions.fixedDelay] — if set, every retry waits exactly this duration.
 *  4. Exponential backoff computed by the shared [BackoffCalculator]:
 *     `baseDelay * 2.0^tryCount` clamped to `maxDelay`, with symmetric ±10% jitter
 *     ([RetrySettings.DEFAULT_JITTER]). This is the same calculator the recovery-aware
 *     `pipeline.step.retry.RetryStep` uses, so both stacks share one backoff formula and one
 *     set of defaults. The deadline-shrinking the calculator also offers is disabled here
 *     (this stage-based step carries no total-timeout budget).
 *
 * ## Failure handling
 *
 * Only [Exception] subclasses are intercepted. [Error] (e.g. [OutOfMemoryError],
 * [StackOverflowError]) propagates immediately — no retry, no classification, no logging.
 *
 * [InterruptedIOException] from the downstream transport is not classified as retryable.
 * The thread interrupt status is restored and the exception is rethrown immediately with
 * any accumulated prior failures attached as suppressed. A bare [InterruptedException]
 * (a downstream blocking call that did not wrap the interrupt) is handled identically: the
 * flag is restored and it is surfaced as [InterruptedIOException] (so the `@Throws(IOException)`
 * contract holds) with the prior failures attached.
 *
 * On non-retryable exception, every prior attempt's exception is attached to the
 * final exception via [Throwable.addSuppressed] before rethrow.
 *
 * ## Thread-safety
 *
 * Stateless after construction. The [ClientLogger] and immutable [options] / [clock] are
 * reused across concurrent calls; per-request state lives on the loop's stack frame.
 *
 * ## Subclassing contract
 *
 * This class is open specifically to allow customisation of its retry-decision and delay
 * extension points. Each `protected open` member has the following invariants:
 *
 * ### [computeResponseDelay]
 * Called when a retryable response has been received and the loop is about to sleep.
 * **Invariants the override MUST preserve:**
 * - Must return a non-negative [Duration]. A negative value is silently treated as zero
 *   by [sleepOrAbort], but the intent is that delays are non-negative.
 * - Must not throw — any exception propagates out of [process] and aborts the request.
 * **Default behaviour:** consults [HttpRetryOptions.delayFromCondition], then the
 * `Retry-After` / `retry-after-ms` response headers, then falls back to fixed or
 * exponential backoff.
 * **When to override:** to apply request-specific delay logic (e.g. reading a custom
 * header not in [HttpRetryOptions.retryAfterHeaders]).
 *
 * ### [computeExceptionDelay]
 * Called when a retryable exception was thrown and the loop is about to sleep.
 * **Invariants the override MUST preserve:**
 * - Must return a non-negative [Duration].
 * - Must not throw.
 * **Default behaviour:** like [computeResponseDelay] but skips header parsing (there is
 * no response to read headers from).
 * **When to override:** same rationale as [computeResponseDelay]; exception-path delays
 * that differ from response-path delays (e.g. back off harder on connection failures).
 *
 * ### [retryAfterFromHeaders]
 * Called by the default [computeResponseDelay] implementation to parse the server-supplied
 * `Retry-After` delay.
 * **Invariants the override MUST preserve:**
 * - May return `null` to fall through to the default backoff; must not throw.
 * - If it returns a non-null [Duration], that value is used verbatim — it should be
 *   non-negative.
 * **Default behaviour:** walks [HttpRetryOptions.retryAfterHeaders] in order and delegates
 * each value to `RetryAfterParser.parseHeaderValue`, which parses it as seconds, RFC 1123
 * date, milliseconds, or a jittered Unix-epoch reset depending on the header name. The first
 * parseable value wins.
 * **When to override:** to support additional server-specific pacing headers beyond those
 * in [HttpRetryOptions.retryAfterHeaders].
 */
public open class DefaultRetryStep
    @JvmOverloads
    constructor(
        options: HttpRetryOptions = HttpRetryOptions(),
        private val clock: Clock = Clock.SYSTEM,
        internal val logger: ClientLogger = ClientLogger(DefaultRetryStep::class),
    ) : RetryStep() {
        /**
         * Effective options. `maxRetries < 0` is clamped to [DEFAULT_MAX_RETRIES] at
         * construction; a verbose log records the clamp so callers can spot config bugs.
         */
        private val options: HttpRetryOptions = clampOptions(options)

        /**
         * The [options]' exponential parameters expressed as a [RetrySettings] view so the shared
         * [BackoffCalculator] can compute this stack's schedule. Built once per step instance:
         *  - `initialDelay` / `maxDelay` come from the options.
         *  - `delayMultiplier` (2.0) and `jitter` (0.2) are the canonical shared constants — the
         *    options object does not expose its own multiplier/jitter, so the SDK defaults apply.
         *  - `totalTimeout = ZERO` disables the deadline cap: the stage-based step has no budget.
         * The `fixedDelay` path never consults this view; it short-circuits in [backoffOrFixed].
         */
        private val backoffSettings: RetrySettings =
            RetrySettings.builder()
                .initialDelay(this.options.baseDelay)
                .maxDelay(this.options.maxDelay)
                .delayMultiplier(RetrySettings.DEFAULT_DELAY_MULTIPLIER)
                .jitter(RetrySettings.DEFAULT_JITTER)
                .totalTimeout(Duration.ZERO)
                .build()

        /**
         * Sends [request] through the downstream pipeline with automatic retry on retryable failures.
         *
         * ## Request immutability invariant
         *
         * Retries re-invoke `next.copy().process()` which reuses the pipeline's in-flight
         * `state.request`. Steps upstream of the retry step MUST NOT mutate the request between
         * retries via `next.process(...)` substitution; doing so leaks the mutation into the retry
         * attempt. The current step ordering avoids this; future steps must respect the same
         * invariant.
         *
         * ## Suppressed exceptions on success
         *
         * When a retry sequence ends with a successful retryable response after one or more prior
         * exception failures, the accumulated `suppressed` exceptions are NOT propagated to the
         * caller; the success-response path discards them. Operators see retry attempts via the
         * `http.retry` log events emitted at each attempt.
         */
        @Throws(IOException::class)
        override fun process(
            request: Request,
            next: PipelineNext,
        ): Response {
            var tryCount = 0
            val retrySequenceStartNanos = clock.monotonic()
            // Lazily allocated on first failure so the success path never pays for the list.
            var suppressed: MutableList<Throwable>? = null

            // A request whose body is single-use and whose method is non-idempotent cannot be
            // safely re-sent: the second writeTo would trip the body's consume-once guard. When
            // that holds, the loop runs exactly one attempt and never retries — mirroring the
            // pipeline-primitives RetryStep.canRetry() invariant.
            val retrySafe = isRetrySafe(request)

            // The retry loop has distinct continue / return paths per attempt outcome
            // (success-retryable / success-final / exception-retryable / exception-final).
            // Flattening to one exit would require duplicating the exception classification.
            @Suppress("LoopWithTooManyJumpStatements")
            while (true) {
                val attemptResult = runOnce(next)

                if (attemptResult.isSuccess) {
                    val response = attemptResult.getOrThrow()
                    val shouldRetry =
                        retrySafe &&
                            tryCount < options.maxRetries &&
                            decideRetryResponse(response, tryCount, suppressed, retrySequenceStartNanos)
                    if (shouldRetry) {
                        tryCount++
                        continue
                    }
                    return response
                }

                val err = attemptResult.exceptionOrNull()!!
                // Errors (OOM, StackOverflow, …) are unrecoverable — never retry, never log,
                // just let them propagate. Only Exception subclasses are intercepted.
                if (err is Error) throw err

                val exception = err as Exception
                // Interrupts are explicitly not retryable per the SDK-wide cancellation
                // convention. Re-interrupt the thread (the catch may have cleared it) and
                // rethrow so the caller's loop can observe cancellation immediately. Both an
                // already-wrapped InterruptedIOException and a bare InterruptedException (from a
                // downstream blocking call that did not wrap the interrupt) are handled here:
                // the flag is restored and cancellation is surfaced as InterruptedIOException so
                // the @Throws(IOException) contract holds, with prior failures attached.
                if (exception is InterruptedIOException || exception is InterruptedException) {
                    Thread.currentThread().interrupt()
                    val ioe = asInterruptedIo(exception)
                    suppressed?.forEach(ioe::addSuppressed)
                    throw ioe
                }
                if (retrySafe && tryCount < options.maxRetries) {
                    val accumulator = suppressed ?: ArrayList<Throwable>().also { suppressed = it }
                    if (decideRetryException(exception, tryCount, accumulator, retrySequenceStartNanos)) {
                        tryCount++
                        continue
                    }
                }

                // Terminal failure path — every prior attempt's exception is attached as
                // suppressed on the rethrown exception so callers see the full trail.
                suppressed?.forEach(exception::addSuppressed)
                // The method is declared `@Throws(IOException::class)`, which becomes
                // `throws IOException` in the generated bytecode. Java callers
                // `catch (IOException e)` expect ONLY `IOException` here — surfacing an
                // `IllegalStateException` (from a misbehaving `shouldRetry` predicate, say)
                // would silently bypass that catch. Wrap any non-IO exception so the
                // checked-exception contract is honored.
                //
                // Non-`IOException` causes are wrapped in `IOException("HTTP pipeline failure", cause)`
                // to honor the `@Throws(IOException::class)` contract; the original cause is
                // attached via the standard Java chained-exception mechanism so callers can
                // retrieve it via `Throwable.getCause()`.
                throw if (exception is IOException) {
                    exception
                } else {
                    IOException("HTTP pipeline failure", exception)
                }
            }
        }

        /**
         * Returns `true` when [request] may be re-sent. A body-less request is retry-safe only
         * when its method is idempotent; a body-bearing request is retry-safe only when its body
         * is replayable — a non-replayable body cannot be re-sent (the second
         * `RequestBody.writeTo` trips the body's consume-once guard and surfaces as a confusing
         * wrapped [IllegalStateException]). Making a re-sent body-bearing request idempotent is
         * the caller's responsibility. Mirrors `pipeline.step.retry.RetryStep.canRetry`.
         */
        private fun isRetrySafe(request: Request): Boolean {
            val body = request.body ?: return request.method in IDEMPOTENT_METHODS
            return body.isReplayable()
        }

        /**
         * Normalises an interrupt-signalling exception to [InterruptedIOException]. An
         * [InterruptedIOException] is returned as-is; a bare [InterruptedException] is wrapped
         * (with the original attached as cause) so the loop can satisfy its `@Throws(IOException)`
         * contract while preserving the cancellation signal.
         */
        private fun asInterruptedIo(exception: Exception): InterruptedIOException =
            when (exception) {
                is InterruptedIOException -> exception
                else -> InterruptedIOException("retry interrupted").apply { initCause(exception) }
            }

        /**
         * Executes a single pipeline attempt and returns the result (success or exception)
         * wrapped in a [Result]. Errors ([Error] subclasses) are NOT caught here — they
         * propagate immediately per the SDK cancellation / error-handling contract.
         */
        private fun runOnce(next: PipelineNext): Result<Response> = runCatching { next.copy().process() }

        /**
         * Decides whether to retry after a successful (but retryable-status) response.
         * If retry is warranted, the response is closed, then the loop sleeps for the computed
         * delay and `true` is returned. Returns `false` when the response should be returned to
         * the caller as-is — in which case the response is left OPEN for the caller to consume.
         *
         * The retryable response is closed BEFORE the backoff sleep so its body's resources
         * (socket / buffer) are not pinned open across a potentially long `Thread.sleep`. The
         * delay is computed from the response headers first, while the response is still open.
         * If the should-retry predicate or [computeResponseDelay] throws, the response is closed
         * before the throwable propagates so the retryable response never leaks.
         */
        private fun decideRetryResponse(
            response: Response,
            tryCount: Int,
            suppressed: List<Throwable>?,
            retrySequenceStartNanos: Long,
        ): Boolean {
            val condition = HttpRetryCondition(response, null, tryCount, suppressed.orEmpty())
            // Compute the delay (may read Retry-After from the response) while the response is
            // still open, then release the response body's resources BEFORE we sleep so the
            // socket/buffer is not held open across the backoff window. Both the should-retry
            // predicate and computeResponseDelay (a subclass override) can throw; if either
            // does we must still close the retryable response — otherwise its socket/buffer
            // leaks. closeQuietly swallows IOException and Response.close is idempotent, so
            // closing on the throw path is safe even though the happy path closes again.
            val delay: Duration =
                try {
                    if (!invokeShouldRetryResponse(condition)) return false
                    computeResponseDelay(condition)
                } catch (t: Throwable) {
                    closeQuietly(response)
                    throw t
                }
            logRetry(tryCount, delay, response.status.code, cause = null, retrySequenceStartNanos)
            closeQuietly(response)
            sleepOrAbort(delay, suppressed)
            return true
        }

        /**
         * Decides whether to retry after an exception. If retry is warranted, records the
         * exception into [accumulator], sleeps for the computed delay, and returns `true`.
         * Returns `false` when the exception should be rethrown as the terminal failure.
         *
         * The current exception is appended to [accumulator] BEFORE sleeping so that an
         * interrupt during sleep doesn't silently discard it.
         */
        private fun decideRetryException(
            exception: Exception,
            tryCount: Int,
            accumulator: MutableList<Throwable>,
            retrySequenceStartNanos: Long,
        ): Boolean {
            val condition = HttpRetryCondition(null, exception, tryCount, accumulator)
            if (!invokeShouldRetryException(condition)) return false
            val delay = computeExceptionDelay(condition)
            logRetry(tryCount, delay, statusCode = -1, cause = exception, retrySequenceStartNanos)
            // Append the current exception BEFORE sleeping. If the sleep is
            // interrupted, `sleepOrAbort` throws InterruptedIOException with the
            // accumulator's exceptions attached as suppressed — recording the
            // current exception first means it's not silently lost on interrupt.
            accumulator.add(exception)
            sleepOrAbort(delay, accumulator)
            return true
        }

        /**
         * Closes [response] and swallows any [IOException], logging at verbose. The caller has
         * already committed to retrying, so a close failure on a soon-to-be-replaced response
         * is not actionable — surfacing it would just confuse stack traces.
         */
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

        // --------------- Should-retry hooks ---------------

        /**
         * Invokes [HttpRetryOptions.shouldRetryCondition] and wraps any thrown exception as
         * [IllegalStateException] — predicates must not destabilise the retry loop.
         */
        private fun invokeShouldRetryResponse(condition: HttpRetryCondition): Boolean =
            invokeShouldRetry(options.shouldRetryCondition, condition)

        private fun invokeShouldRetryException(condition: HttpRetryCondition): Boolean =
            invokeShouldRetry(options.shouldRetryException, condition)

        private fun invokeShouldRetry(
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

        // --------------- Delay computation ---------------

        /**
         * Computes the delay before retrying [condition.response]. Resolution order:
         *  1. [HttpRetryOptions.delayFromCondition] override (if non-null).
         *  2. `Retry-After` header parsing.
         *  3. [HttpRetryOptions.fixedDelay] or exponential backoff.
         */
        protected open fun computeResponseDelay(condition: HttpRetryCondition): Duration {
            invokeDelayFromCondition(condition)?.let { return it }
            condition.response?.let { retryAfterFromHeaders(it) }?.let { return it }
            return backoffOrFixed(condition.tryCount)
        }

        /**
         * Computes the delay before retrying [condition.exception]. There is no header to
         * consult, so the resolution order skips step (2) of [computeResponseDelay].
         */
        protected open fun computeExceptionDelay(condition: HttpRetryCondition): Duration {
            invokeDelayFromCondition(condition)?.let { return it }
            return backoffOrFixed(condition.tryCount)
        }

        private fun invokeDelayFromCondition(condition: HttpRetryCondition): Duration? =
            try {
                options.delayFromCondition.delayFor(condition)
            } catch (t: Throwable) {
                // `is Error` is the canonical JVM idiom for splitting catastrophic Errors from
                // user-recoverable Exceptions; detekt's preference for class hierarchy doesn't
                // apply when classifying for retry/rethrow.
                @Suppress("InstanceOfCheckForException")
                if (t is Error) throw t
                // Don't fail the whole pipeline if the user override misbehaves — fall back to
                // the default delay calculation. Log loud enough that the bug is observable.
                logger.atWarning()
                    .event("http.retry.delay_override_failed")
                    .field("error.type", t::class.java.simpleName ?: "Throwable")
                    .cause(t)
                    .log()
                null
            }

        /**
         * Returns [HttpRetryOptions.fixedDelay] if set, otherwise the exponential-backoff delay
         * for [tryCount]. The backoff is computed by the shared [BackoffCalculator] from
         * [backoffSettings] so this stack and the recovery-aware `RetryStep` share one formula.
         *
         * [tryCount] is 0-indexed here (`0` = the delay before the first retry), whereas
         * [BackoffCalculator.computeDelay] is 1-indexed (`1` = first retry); the `+ 1` bridges
         * the two so both produce `baseDelay`, `2·baseDelay`, `4·baseDelay`, … capped at `maxDelay`.
         */
        private fun backoffOrFixed(tryCount: Int): Duration =
            options.fixedDelay ?: BackoffCalculator.computeDelay(tryCount + 1, backoffSettings)

        // --------------- Retry-After parsing ---------------

        /**
         * Walks [HttpRetryOptions.retryAfterHeaders] in order, returning the first parseable
         * delay value. Parse failures, negative values, or empty values fall through to the
         * next header; if every header is missing or unparseable, returns null and the caller
         * falls back to the default delay.
         *
         * Each header value is parsed by [RetryAfterParser.parseHeaderValue] — the single,
         * fully-guarded source of truth shared with the recovery-aware `RetryStep`. That parser
         * dispatches on the header name (`Retry-After` → seconds-or-date, the `*-ms` variants →
         * milliseconds, `X-RateLimit-Reset` → jittered Unix epoch, anything else → milliseconds)
         * and is total: out-of-range or malformed values yield `null` rather than throwing.
         */
        protected open fun retryAfterFromHeaders(response: Response): Duration? {
            val now = clock.now()
            for (name in options.retryAfterHeaders) {
                val raw = response.headers.get(name) ?: continue
                RetryAfterParser.parseHeaderValue(name, raw, now)?.let { return it }
            }
            return null
        }

        // --------------- Sleep + interrupt handling ---------------

        /**
         * Sleeps for [delay]; on [InterruptedException] preserves the interrupt status and
         * throws [InterruptedIOException] with [accumulatedFailures] (if any) attached as
         * suppressed. Retries are aborted — interrupts are explicitly not retryable per the
         * SDK-wide cancellation convention.
         */
        @Throws(InterruptedIOException::class)
        private fun sleepOrAbort(
            delay: Duration,
            accumulatedFailures: List<Throwable>?,
        ) {
            if (delay.isZero || delay.isNegative) return
            try {
                clock.sleep(delay)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                val ioe = InterruptedIOException("retry interrupted").apply { initCause(ie) }
                accumulatedFailures?.forEach(ioe::addSuppressed)
                throw ioe
            }
        }

        // --------------- Logging ---------------

        private fun logRetry(
            tryCount: Int,
            delay: Duration,
            statusCode: Int,
            cause: Throwable?,
            sequenceStartNanos: Long,
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

        // --------------- Options clamping ---------------

        private fun clampOptions(opts: HttpRetryOptions): HttpRetryOptions {
            if (opts.maxRetries >= 0) return opts
            logger.atVerbose()
                .event("http.retry.maxRetries_clamped")
                .field("http.retry.max_retries.requested", opts.maxRetries.toLong())
                .field("http.retry.max_retries.applied", DEFAULT_MAX_RETRIES.toLong())
                .log()
            // HttpRetryOptions isn't a data class, so an explicit copy via the constructor is
            // the cheapest correct fix — re-using every other field as-is.
            return HttpRetryOptions(
                maxRetries = DEFAULT_MAX_RETRIES,
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
            /**
             * Default retry count applied when the caller passes a negative
             * [HttpRetryOptions.maxRetries], and the value baked into the no-arg
             * [HttpRetryOptions] default. `2` retries on top of the initial send is the SDK's
             * canonical budget — `initial + DEFAULT_MAX_RETRIES == 3`, matching
             * [RetrySettings.DEFAULT_MAX_ATTEMPTS] so both retry stacks default to the same
             * number of total sends.
             */
            public const val DEFAULT_MAX_RETRIES: Int = 2

            // Nanoseconds in one millisecond — used to convert monotonic-clock deltas to ms
            // for retry log events.
            private const val NANOS_PER_MILLI = 1_000_000L

            // Methods safe to re-send regardless of body replayability (idempotent per RFC 9110).
            // A request using one of these may always be retried; others require a replayable
            // body. Mirrors RetrySettings.DEFAULT_RETRYABLE_METHODS.
            private val IDEMPOTENT_METHODS: Set<Method> =
                setOf(Method.GET, Method.HEAD, Method.OPTIONS, Method.PUT, Method.DELETE)
        }
    }
