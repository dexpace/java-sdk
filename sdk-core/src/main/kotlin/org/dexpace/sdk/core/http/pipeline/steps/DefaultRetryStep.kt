package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.DateTimeRfc1123
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom

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
 *  3. Closes a retryable response before sleeping (releases the body's resources).
 *  4. Sleeps via [Clock.sleep]; an interrupt during sleep throws [InterruptedIOException]
 *     with the original [InterruptedException] and any accumulated prior failures attached
 *     as suppressed — retries are NOT resumed after an interrupt.
 *  5. Caps `tryCount` at [MAX_SHIFT_TRY_COUNT] before computing `1L shl tryCount` so the
 *     left-shift can never overflow (the resulting delay is clamped to [HttpRetryOptions.maxDelay]
 *     anyway, so the cap is invisible to callers).
 *
 * ## Delay precedence (highest to lowest)
 *
 *  1. [HttpRetryOptions.delayFromCondition] — caller-supplied override; if it returns
 *     non-null, that delay is used verbatim.
 *  2. [HttpHeaderName.RETRY_AFTER] / [HttpHeaderName.RETRY_AFTER_MS] / [HttpHeaderName.X_MS_RETRY_AFTER_MS]
 *     parsed from the response (response path only). A negative or unparseable value
 *     falls through; a value of zero produces an immediate retry.
 *  3. [HttpRetryOptions.fixedDelay] — if set, every retry waits exactly this duration.
 *  4. Exponential backoff: `baseDelay * (1L shl tryCount)` clamped to `maxDelay`, with
 *     ±5% random jitter via [ThreadLocalRandom].
 *
 * ## Failure handling
 *
 * Only [Exception] subclasses are intercepted. [Error] (e.g. [OutOfMemoryError],
 * [StackOverflowError]) propagates immediately — no retry, no classification, no logging.
 *
 * [InterruptedIOException] from the downstream transport is not classified as retryable.
 * The thread interrupt status is restored and the exception is rethrown immediately with
 * any accumulated prior failures attached as suppressed.
 *
 * On non-retryable exception, every prior attempt's exception is attached to the
 * final exception via [Throwable.addSuppressed] before rethrow.
 *
 * ## Thread-safety
 *
 * Stateless after construction. The [ClientLogger] and immutable [options] / [clock] are
 * reused across concurrent calls; per-request state lives on the loop's stack frame.
 */
open class DefaultRetryStep @JvmOverloads constructor(
    options: HttpRetryOptions = HttpRetryOptions(),
    private val clock: Clock = Clock.SYSTEM,
) : RetryStep() {

    private val logger = ClientLogger(DefaultRetryStep::class)

    /**
     * Effective options. `maxRetries < 0` is clamped to [DEFAULT_MAX_RETRIES] at
     * construction; a verbose log records the clamp so callers can spot config bugs.
     */
    private val options: HttpRetryOptions = clampOptions(options)

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        var tryCount = 0
        // Lazily allocated on first failure so the success path never pays for the list.
        var suppressed: MutableList<Throwable>? = null

        while (true) {
            val attemptResult = runCatching { next.copy().process() }

            if (attemptResult.isSuccess) {
                val response = attemptResult.getOrThrow()
                if (tryCount < options.maxRetries) {
                    val condition = HttpRetryCondition(response, null, tryCount, suppressed.orEmpty())
                    if (invokeShouldRetryResponse(condition)) {
                        val delay = computeResponseDelay(condition)
                        logRetry(tryCount, delay, response.status.code, cause = null)
                        // Release the failed-response body's resources before we sleep.
                        closeQuietly(response)
                        sleepOrAbort(delay, suppressed)
                        tryCount++
                        continue
                    }
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
            // rethrow so the caller's loop can observe cancellation immediately.
            if (exception is InterruptedIOException) {
                Thread.currentThread().interrupt()
                suppressed?.forEach(exception::addSuppressed)
                throw exception
            }
            if (tryCount < options.maxRetries) {
                val condition = HttpRetryCondition(null, exception, tryCount, suppressed.orEmpty())
                if (invokeShouldRetryException(condition)) {
                    val delay = computeExceptionDelay(condition)
                    logRetry(tryCount, delay, statusCode = -1, cause = exception)
                    val accumulator = suppressed ?: ArrayList<Throwable>().also { suppressed = it }
                    // Append the current exception BEFORE sleeping. If the sleep is
                    // interrupted, `sleepOrAbort` throws InterruptedIOException with the
                    // accumulator's exceptions attached as suppressed — recording the
                    // current exception first means it's not silently lost on interrupt.
                    accumulator.add(exception)
                    sleepOrAbort(delay, accumulator)
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
            throw if (exception is IOException) exception
            else IOException("HTTP pipeline failure", exception)
        }
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
        predicate: (HttpRetryCondition) -> Boolean,
        condition: HttpRetryCondition,
    ): Boolean = try {
        predicate(condition)
    } catch (t: Throwable) {
        // Error subclasses still rethrown; an OOM in the predicate must not be wrapped.
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

    private fun invokeDelayFromCondition(condition: HttpRetryCondition): Duration? = try {
        options.delayFromCondition(condition)
    } catch (t: Throwable) {
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
     * Returns [HttpRetryOptions.fixedDelay] if set, otherwise the exponential-backoff
     * delay for [tryCount]. The shift count is capped at [MAX_SHIFT_TRY_COUNT] so the
     * `1L shl tryCount` term never overflows; the result is always clamped to [HttpRetryOptions.maxDelay]
     * anyway, so the cap is invisible in practice.
     */
    private fun backoffOrFixed(tryCount: Int): Duration =
        options.fixedDelay ?: exponentialBackoff(tryCount)

    /**
     * `baseDelay * (1L shl tryCount)` clamped to `maxDelay`, plus a ±5% jitter sampled
     * from [ThreadLocalRandom]. Pure function of [tryCount] and the configured options.
     */
    private fun exponentialBackoff(tryCount: Int): Duration {
        val baseNanos = options.baseDelay.toNanos()
        if (baseNanos == 0L) return Duration.ZERO
        val maxNanos = options.maxDelay.toNanos()
        val safeShift = tryCount.coerceAtMost(MAX_SHIFT_TRY_COUNT)
        // 1L shl 30 ~= 1e9 — multiplying by 800ms (8e8 ns) overflows. Cap on the long
        // multiply itself: if `baseNanos * (1L shl safeShift)` would overflow, clamp.
        val multiplier = 1L shl safeShift
        val scaled = if (baseNanos > 0 && multiplier > Long.MAX_VALUE / baseNanos) {
            Long.MAX_VALUE
        } else {
            baseNanos * multiplier
        }
        val clamped = scaled.coerceAtMost(maxNanos)
        val jittered = applyJitter(clamped)
        // Guarantee a non-negative result — jitter could push us under zero if the caller
        // configured pathological options (e.g. baseDelay equal to negative epsilon).
        return Duration.ofNanos(jittered.coerceAtLeast(0L))
    }

    /**
     * Applies a ±5% jitter to [nanos]. Sample is drawn from [ThreadLocalRandom] which is
     * per-thread, so there is no cross-thread contention on the retry hot path.
     */
    private fun applyJitter(nanos: Long): Long {
        if (nanos == 0L) return 0L
        // 5% of nanos, used as the magnitude bound on the random sample.
        val jitterMagnitude = nanos / 20L
        if (jitterMagnitude == 0L) return nanos
        // ThreadLocalRandom.nextLong(origin, bound) is inclusive of origin, exclusive of bound.
        val offset = ThreadLocalRandom.current().nextLong(-jitterMagnitude, jitterMagnitude + 1L)
        return nanos + offset
    }

    // --------------- Retry-After parsing ---------------

    /**
     * Walks [HttpRetryOptions.retryAfterHeaders] in order, returning the first parseable
     * delay value. Parse failures, negative values, or empty values fall through to the
     * next header; if every header is missing or unparseable, returns null and the caller
     * falls back to the default delay.
     */
    protected open fun retryAfterFromHeaders(response: Response): Duration? {
        for (name in options.retryAfterHeaders) {
            val raw = response.headers.get(name) ?: continue
            parseRetryAfterValue(name, raw)?.let { return it }
        }
        return null
    }

    /**
     * Parses a single `Retry-After` header value. The standard `Retry-After` form accepts
     * integer seconds OR an RFC 1123 date; the `*-ms` variants accept integer milliseconds.
     */
    private fun parseRetryAfterValue(name: HttpHeaderName, raw: String): Duration? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return when (name) {
            HttpHeaderName.RETRY_AFTER -> parseSecondsOrDate(value)
            HttpHeaderName.RETRY_AFTER_MS, HttpHeaderName.X_MS_RETRY_AFTER_MS -> parseMillis(value)
            else -> parseMillis(value)
        }
    }

    /**
     * Parses [value] as either a non-negative integer count of seconds OR an RFC 1123
     * date-time. A zero result is permitted (immediate retry); negative numeric values
     * and unparseable inputs return null. Very large values are respected but logged at
     * verbose so a misbehaving server is easy to spot.
     */
    private fun parseSecondsOrDate(value: String): Duration? {
        val asLong = tryParseLong(value)
        if (asLong != null) {
            if (asLong < 0L) return null
            return secondsDurationOrWarn(asLong)
        }
        return try {
            val target = DateTimeRfc1123.parse(value)
            val now = clock.now()
            // If the server's "retry-after-date" is in the past, treat as immediate retry.
            if (target.isBefore(now)) Duration.ZERO else durationBetween(now, target)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseMillis(value: String): Duration? {
        val ms = tryParseLong(value) ?: return null
        if (ms < 0L) return null
        return Duration.ofMillis(ms)
    }

    private fun tryParseLong(value: String): Long? = try {
        value.toLong()
    } catch (_: NumberFormatException) {
        null
    }

    /**
     * [Duration.between] preserves nanosecond precision; the warn-on-large hook reads the
     * coarser seconds component so a misbehaving upstream (multi-hour `Retry-After`) shows
     * up in logs without losing fractional precision in the returned duration.
     */
    private fun durationBetween(from: Instant, to: Instant): Duration {
        val delta = Duration.between(from, to)
        warnIfLargeRetryAfter(delta.seconds)
        return delta
    }

    private fun secondsDurationOrWarn(seconds: Long): Duration {
        warnIfLargeRetryAfter(seconds)
        return Duration.ofSeconds(seconds)
    }

    private fun warnIfLargeRetryAfter(seconds: Long) {
        if (seconds <= LARGE_RETRY_AFTER_SECONDS) return
        logger.atVerbose()
            .event("http.retry.large_retry_after")
            .field("retry_after.seconds", seconds)
            .log()
    }

    // --------------- Sleep + interrupt handling ---------------

    /**
     * Sleeps for [delay]; on [InterruptedException] preserves the interrupt status and
     * throws [InterruptedIOException] with [accumulatedFailures] (if any) attached as
     * suppressed. Retries are aborted — interrupts are explicitly not retryable per the
     * SDK-wide cancellation convention.
     */
    @Throws(InterruptedIOException::class)
    private fun sleepOrAbort(delay: Duration, accumulatedFailures: List<Throwable>?) {
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

    private fun logRetry(tryCount: Int, delay: Duration, statusCode: Int, cause: Throwable?) {
        val event = logger.atInfo()
            .event("http.retry")
            .field("http.retry.try_count", tryCount.toLong())
            .field("http.retry.delay_ms", delay.toMillis())
        if (statusCode > 0) {
            event.field("http.response.status_code", statusCode.toLong())
        }
        if (cause != null) {
            event.field("error.type", cause::class.java.simpleName ?: "Throwable")
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

    companion object {
        /**
         * Default [HttpRetryOptions.maxRetries] applied when the caller passes a negative
         * value. Matches Azure Core's `RetryOptions` default.
         */
        const val DEFAULT_MAX_RETRIES: Int = 3

        /**
         * Upper bound on `tryCount` used for the `1L shl tryCount` term in
         * [DefaultRetryStep.exponentialBackoff]. `1L shl 30` ~= 1.07e9 — the scaled delay is
         * always clamped to [HttpRetryOptions.maxDelay] long before this bound is hit, so the
         * cap is a paranoid guard against integer overflow rather than a behavior knob.
         */
        const val MAX_SHIFT_TRY_COUNT: Int = 30

        /**
         * Threshold above which a `Retry-After` value triggers a verbose log. Values larger
         * than this almost always indicate either a misbehaving upstream or a long
         * maintenance window — callers may want to override via [HttpRetryOptions.delayFromCondition].
         */
        const val LARGE_RETRY_AFTER_SECONDS: Long = 60L * 60L
    }
}
