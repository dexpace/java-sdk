package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.http.response.exception.NetworkException
import org.dexpace.sdk.core.pipeline.ResponseOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Behavioural tests for [RetryStep]. Uses a hand-rolled fake [HttpClient] that records each
 * call and yields a queue of canned outcomes — letting us assert call counts, retry-or-not
 * semantics, and propagated exceptions.
 *
 * Tests pass a [ScheduledExecutorService] via [RetrySettings] when they need to observe the
 * scheduled delay precisely; otherwise they use a zero-delay scheduler so retries fire
 * promptly without blocking the test thread on real time.
 */
class RetryStepTest {
    // region -- test fakes --

    private sealed class Canned {
        data class Ok(val response: Response) : Canned()

        data class Err(val error: Throwable) : Canned()
    }

    /** Simple fake transport: queues outcomes, records every request, throws on under-feed. */
    private class FakeClient(
        entries: List<Canned> = emptyList(),
    ) : HttpClient {
        private val queue: ArrayDeque<Canned> = ArrayDeque(entries)
        val calls: MutableList<Request> = ArrayList()

        override fun execute(request: Request): Response {
            calls.add(request)
            val next = queue.removeFirstOrNull() ?: error("FakeClient: queue exhausted")
            return when (next) {
                is Canned.Ok -> next.response
                is Canned.Err -> throw next.error
            }
        }
    }

    /**
     * Scheduler that captures every `schedule(Runnable, delay, unit)` and runs the
     * runnable inline — so retry delays do not actually block the test thread.
     *
     * The captured delays let tests assert that the right wait was scheduled. The fall-back
     * path of [ScheduledExecutorService] (newSingleThreadScheduledExecutor) would also work
     * but it forces real wall-clock waits, which are slow and racey.
     */
    private class InstantScheduler : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
        /** Delays observed by [schedule], in declared order. */
        val scheduledDelaysNanos: MutableList<Long> = ArrayList()

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            scheduledDelaysNanos.add(unit.toNanos(delay))
            // Run the runnable inline — this completes the CompletableFuture inside RetryStep
            // immediately, so the retry fires without real wall-clock waiting.
            command.run()
            // Return an already-completed future stub.
            return CompletedScheduledFuture
        }
    }

    private object CompletedScheduledFuture : ScheduledFuture<Any?> {
        override fun compareTo(other: java.util.concurrent.Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }

    private fun requestGet(): Request =
        Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.GET)
            .build()

    private fun requestPostNonReplayable(): Request {
        // BufferedSourceRequestBody is single-use / non-replayable.
        val body =
            RequestBody.create(
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)).let { stream ->
                    val nonMarkable =
                        object : java.io.InputStream() {
                            override fun read(): Int = stream.read()

                            override fun markSupported(): Boolean = false
                        }
                    nonMarkable
                },
                3L,
            )
        return Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.POST)
            .body(body)
            .build()
    }

    private fun response(
        status: Int,
        headers: Headers = Headers.builder().build(),
        request: Request = requestGet(),
    ): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(status))
            .headers(headers)
            .build()

    private fun httpException(
        status: Int,
        headers: Headers = Headers.builder().build(),
    ): HttpException = HttpExceptionFactory.fromResponse(response(status, headers))

    private fun zeroDelaySettings(scheduler: ScheduledExecutorService? = null): RetrySettings =
        RetrySettings.builder()
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .delayMultiplier(1.0)
            .jitter(0.0)
            .maxAttempts(DEFAULT_MAX_ATTEMPTS)
            .totalTimeout(Duration.ZERO)
            .scheduler(scheduler)
            .build()

    // endregion

    // region -- core retry semantics --

    @Test
    fun `503 with replayable GET body retries up to maxAttempts then propagates`() {
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                ),
            )
        val request = requestGet()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val initialOutcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE))
        // Caller (the pipeline) passed the very first failure to invoke().
        // RetryStep then retries (maxAttempts-1) additional times.
        val out = step.invoke(initialOutcome)
        assertTrue(out is ResponseOutcome.Failure, "Retries should propagate the last failure")
        // maxAttempts = 3 → 2 retries (calls.size == 2 because the initial call was made by
        // the caller before invoke()).
        assertEquals(DEFAULT_MAX_ATTEMPTS - 1, client.calls.size)
    }

    @Test
    fun `503 with non-replayable POST body is not retried`() {
        val client = FakeClient()
        val request = requestPostNonReplayable()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE))
        val out = step.invoke(outcome)
        // Non-replayable POST: no retry attempted; transport never invoked.
        assertSame(outcome, out, "Outcome must be unchanged when body is non-replayable")
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `404 is not retried`() {
        val client = FakeClient()
        val request = requestGet()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(httpException(SC_NOT_FOUND))
        val out = step.invoke(outcome)
        assertSame(outcome, out, "404 must pass through unchanged")
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `NetworkException is retried`() {
        val ok = response(SC_OK)
        // First retry succeeds — single Ok entry covers attempt #1.
        val client =
            FakeClient(
                listOf(
                    Canned.Ok(ok),
                ),
            )
        val request = requestGet()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(NetworkException("connection refused"))
        val out = step.invoke(outcome)
        assertTrue(out is ResponseOutcome.Success)
        assertSame(ok, (out as ResponseOutcome.Success).response)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `success outcome is pass-through`() {
        val client = FakeClient()
        val ok = response(SC_OK)
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val outcome = ResponseOutcome.Success(ok)
        val out = step.invoke(outcome)
        assertSame(outcome, out)
        assertTrue(client.calls.isEmpty())
    }

    // endregion

    // region -- Retry-After header observed by scheduler --

    @Test
    fun `Retry-After 2 seconds drives scheduler delay of 2 seconds`() {
        val ok = response(SC_OK)
        val client = FakeClient(listOf(Canned.Ok(ok)))
        val headers = Headers.builder().set("Retry-After", "2").build()
        val scheduler = InstantScheduler()
        val step =
            RetryStep(
                client,
                zeroDelaySettings(scheduler),
                requestGet(),
            )
        val outcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE, headers))
        val out = step.invoke(outcome)
        assertTrue(out is ResponseOutcome.Success)
        // Exactly one scheduled wait — the 2-second Retry-After.
        assertEquals(1, scheduler.scheduledDelaysNanos.size)
        assertEquals(TimeUnit.SECONDS.toNanos(2), scheduler.scheduledDelaysNanos[0])
    }

    // endregion

    // region -- totalTimeout exceeded mid-retry --

    @Test
    fun `totalTimeout exceeded mid-retry propagates the last failure`() {
        // Construct a clock that jumps far ahead AFTER the first attempt, so the deadline
        // check inside prepareNextAttempt aborts before any further call goes through.
        val now = Instant.parse("2024-06-01T12:00:00Z")
        val advanced = AtomicReference<Instant>(now)
        val testClock: Clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId): Clock = this

                override fun instant(): Instant = advanced.get()
            }
        val client = FakeClient() // no canned responses — must not be called
        val settings =
            RetrySettings.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(100))
                .delayMultiplier(1.0)
                .jitter(0.0)
                .totalTimeout(Duration.ofSeconds(1))
                .maxAttempts(DEFAULT_MAX_ATTEMPTS_TIMEOUT)
                .scheduler(InstantScheduler())
                .build()
        val step = RetryStep(client, settings, requestGet(), clock = testClock)
        // Move the clock past the deadline before RetryStep evaluates it.
        advanced.set(now.plus(Duration.ofSeconds(2)))
        val failure = httpException(SC_SERVICE_UNAVAILABLE)
        val outcome = ResponseOutcome.Failure(failure)
        val out = step.invoke(outcome)
        assertTrue(out is ResponseOutcome.Failure, "Retry should abort when deadline is exhausted")
        assertSame(failure, (out as ResponseOutcome.Failure).error)
        assertTrue(client.calls.isEmpty(), "Transport must not be called when deadline already exhausted")
    }

    // endregion

    // region -- interrupt during wait --

    @Test
    fun `interrupt during wait yields InterruptedIOException and restores flag`() {
        val client = FakeClient()

        // Custom scheduler that blocks forever — the RetryStep's get() call will be the one
        // the test interrupts.
        val blockingScheduler =
            object : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> {
                    // Never run the runnable — the future inside RetryStep stays pending.
                    return BlockingScheduledFuture
                }
            }

        val settings =
            RetrySettings.builder()
                .initialDelay(Duration.ofSeconds(60))
                .maxDelay(Duration.ofSeconds(60))
                .delayMultiplier(1.0)
                .jitter(0.0)
                .totalTimeout(Duration.ZERO)
                .scheduler(blockingScheduler)
                .build()
        val step = RetryStep(client, settings, requestGet())

        // Run the retry on a separate thread so we can interrupt it.
        val started = CountDownLatch(1)
        val resultRef = AtomicReference<ResponseOutcome?>(null)
        val errorRef = AtomicReference<Throwable?>(null)
        val interruptObservedRef = AtomicReference<Boolean>(null)
        val worker =
            Thread {
                started.countDown()
                try {
                    val out = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
                    resultRef.set(out)
                    // Capture the interrupt flag BEFORE invoking any operation that clears it.
                    interruptObservedRef.set(Thread.currentThread().isInterrupted)
                } catch (t: Throwable) {
                    errorRef.set(t)
                }
            }
        worker.start()
        started.await()
        // Give the worker a tick to reach the get() call.
        Thread.sleep(INTERRUPT_DELAY_MS)
        worker.interrupt()
        worker.join(INTERRUPT_JOIN_TIMEOUT_MS)

        val result = resultRef.get()
        assertTrue(
            result is ResponseOutcome.Failure,
            "RetryStep must surface a Failure when the wait is interrupted",
        )
        val error = (result as ResponseOutcome.Failure).error
        assertTrue(
            error is InterruptedIOException,
            "Surfaced error must be InterruptedIOException (got ${error::class.qualifiedName})",
        )
        assertTrue(interruptObservedRef.get() == true, "Worker thread's interrupt flag must remain set")
    }

    private object BlockingScheduledFuture : ScheduledFuture<Any?> {
        override fun compareTo(other: java.util.concurrent.Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = Long.MAX_VALUE

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }

    // endregion

    // region -- attempt() direct entry point --

    @Test
    fun `attempt success returns the response`() {
        val ok = response(SC_OK)
        val client = FakeClient(listOf(Canned.Ok(ok)))
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val out = step.attempt()
        assertSame(ok, out)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `attempt with retries returns the final response`() {
        val ok = response(SC_OK)
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Ok(ok),
                ),
            )
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val out = step.attempt()
        assertSame(ok, out)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `attempt throws when retries exhausted`() {
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                ),
            )
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val ex = assertThrows(HttpException::class.java) { step.attempt() }
        assertEquals(SC_SERVICE_UNAVAILABLE, ex.status.code)
        assertEquals(DEFAULT_MAX_ATTEMPTS, client.calls.size)
    }

    // endregion

    // region -- non-classified errors --

    @Test
    fun `non-HttpException non-NetworkException error is pass-through`() {
        val client = FakeClient()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val unknown = IOException("unknown")
        val outcome = ResponseOutcome.Failure(unknown)
        val out = step.invoke(outcome)
        // Plain IOException is not classified retryable by RetryStep (only NetworkException
        // and HttpException with retryable=true). Should pass through.
        assertSame(outcome, out)
        assertFalse(out is ResponseOutcome.Success)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `HttpException with retryable=false is pass-through even on a retryable status`() {
        // Synthesise an HttpException whose `retryable` is false but status is 503.
        // The factory always produces retryable=true for 503; we use a custom subclass to
        // simulate a caller-overridden classifier.
        val nonRetryable =
            object : HttpException(
                status = Status.SERVICE_UNAVAILABLE,
                headers = Headers.builder().build(),
                body = null,
                retryable = false,
            ) {}
        val client = FakeClient()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val outcome = ResponseOutcome.Failure(nonRetryable)
        val out = step.invoke(outcome)
        assertSame(outcome, out)
        assertTrue(client.calls.isEmpty())
    }

    // endregion

    private companion object {
        // HTTP status code constants for tests.
        private const val SC_OK = 200
        private const val SC_NOT_FOUND = 404
        private const val SC_SERVICE_UNAVAILABLE = 503

        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_MAX_ATTEMPTS_TIMEOUT = 3

        // Interrupt-test timings — choosing modest values keeps the suite quick while still
        // giving the worker thread plenty of time to reach the get() call.
        private const val INTERRUPT_DELAY_MS = 50L
        private const val INTERRUPT_JOIN_TIMEOUT_MS = 2000L
    }
}
