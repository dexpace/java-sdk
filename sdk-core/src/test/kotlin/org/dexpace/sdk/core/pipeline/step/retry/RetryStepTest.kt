/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
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
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun requestPutNonReplayable(): Request {
        // OneShotInputStreamRequestBody (non-markable stream) is single-use / non-replayable.
        // PUT is idempotent, so this isolates the body-replayability gate from the method gate.
        val body =
            RequestBody.create(
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)).let { stream ->
                    object : java.io.InputStream() {
                        override fun read(): Int = stream.read()

                        override fun markSupported(): Boolean = false
                    }
                },
                3L,
            )
        return Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.PUT)
            .body(body)
            .build()
    }

    private fun requestPostBodyless(): Request =
        Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.POST)
            .build()

    private fun requestPutBodyless(): Request =
        Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.PUT)
            .build()

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
    fun `503 with non-replayable PUT body is not retried despite PUT being idempotent`() {
        // Both gates are required: PUT is in retryableMethods (idempotent), but a non-replayable
        // body physically cannot be re-sent. Method idempotency alone must NOT widen eligibility
        // — the failure passes through unchanged and the transport is never re-invoked (which
        // would otherwise trip the body's consume-once guard).
        val client = FakeClient()
        val request = requestPutNonReplayable()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE))
        val out = step.invoke(outcome)
        assertSame(outcome, out, "Outcome must be unchanged when an idempotent method carries a non-replayable body")
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `503 with body-less POST is not retried because POST is non-idempotent`() {
        // Body-less retry safety keys off METHOD idempotency, not off the absence of a body. A
        // bare POST has no payload to re-send, but it is non-idempotent, so it must NOT be retried
        // — a second POST could duplicate a side effect the server already applied. Exercises the
        // body == null branch of canRetry on a non-idempotent method. Mirrors the
        // `body-less POST is NOT retried` case in the http.pipeline DefaultRetryStep suite.
        val client = FakeClient()
        val request = requestPostBodyless()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE))
        val out = step.invoke(outcome)
        assertSame(outcome, out, "Outcome must be unchanged — a body-less POST is non-idempotent")
        assertTrue(client.calls.isEmpty(), "body-less POST must not be re-dispatched")
    }

    @Test
    fun `503 with body-less PUT is retried because PUT is idempotent`() {
        // Control for the body == null branch: with no body the gate falls through to method
        // idempotency. PUT is idempotent, so a body-less PUT is retry-safe and retries normally —
        // here the single retry succeeds. Mirrors the `body-less PUT IS retried` control in the
        // http.pipeline DefaultRetryStep suite.
        val ok = response(SC_OK)
        val client = FakeClient(listOf(Canned.Ok(ok)))
        val request = requestPutBodyless()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), request)
        val outcome = ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE))
        val out = step.invoke(outcome)
        assertTrue(out is ResponseOutcome.Success, "body-less PUT must retry — PUT is idempotent")
        assertSame(ok, (out as ResponseOutcome.Success).response)
        assertEquals(1, client.calls.size)
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

    @Test
    fun `reused RetryStep retries correctly on a second top-level call`() {
        // C-3: the step holds no per-call state on the instance, so invoking it a second time
        // after the first call exhausted its attempt budget must start from a fresh budget and
        // retry again — not silently abort because a stale attempt count was carried over.
        val client =
            FakeClient(
                listOf(
                    // First invoke(): one retry then success (attempt budget = 3 → 2 retries
                    // available, success on the first retry).
                    Canned.Ok(response(SC_OK)),
                    // Second invoke(): the retry must fire again from a clean budget.
                    Canned.Ok(response(SC_OK)),
                ),
            )
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())

        val first = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(first is ResponseOutcome.Success, "First call should recover via retry")
        assertEquals(1, client.calls.size)

        val second = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(second is ResponseOutcome.Success, "Second call must retry again from a fresh budget")
        assertEquals(2, client.calls.size, "Reused step must dispatch a retry on the second call too")
    }

    @Test
    fun `reused RetryStep exhausts the full attempt budget on each call`() {
        // Each top-level invoke() gets maxAttempts-1 retries; reuse must not shrink the budget.
        val client =
            FakeClient(
                listOf(
                    // First invoke(): two retries (both 503), then propagate.
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    // Second invoke(): two more retries, then propagate.
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                ),
            )
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())

        val first = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(first is ResponseOutcome.Failure)
        assertEquals(DEFAULT_MAX_ATTEMPTS - 1, client.calls.size)

        val second = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(second is ResponseOutcome.Failure)
        // The second call must again dispatch maxAttempts-1 retries (4 total across both calls).
        assertEquals((DEFAULT_MAX_ATTEMPTS - 1) * 2, client.calls.size)
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

    @Test
    fun `extreme X-RateLimit-Reset header does not mask the upstream failure`() {
        // P-1: a hostile/out-of-range pacing header must never throw out of the retry loop.
        // Long.MAX_VALUE makes RetryAfterParser return null (no usable hint); the loop falls
        // back to its exponential schedule and still completes the retry to success.
        val ok = response(SC_OK)
        val client = FakeClient(listOf(Canned.Ok(ok)))
        val headers = Headers.builder().set("X-RateLimit-Reset", Long.MAX_VALUE.toString()).build()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())

        val out = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE, headers)))

        assertTrue(out is ResponseOutcome.Success, "Retry must complete despite the out-of-range pacing header")
        assertEquals(1, client.calls.size)
    }

    // endregion

    // region -- totalTimeout exceeded mid-retry --

    @Test
    fun `totalTimeout exceeded mid-retry propagates the last failure`() {
        // The retry budget starts when invoke() is entered (per-call AttemptState captures the
        // start instant on its first clock read). A clock that jumps past the deadline on its
        // SECOND read therefore exhausts the budget inside prepareNextAttempt before any call
        // goes through — exactly the "deadline already exhausted" abort path.
        val now = Instant.parse("2024-06-01T12:00:00Z")
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val testClock: Clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId): Clock = this

                // First read (AttemptState start) = now; subsequent reads jump 2s past the
                // 1s deadline so the elapsed check aborts the very first retry.
                override fun instant(): Instant =
                    if (reads.getAndIncrement() == 0) now else now.plus(Duration.ofSeconds(2))
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
    fun `HttpException with a non-retryable status is pass-through`() {
        // A non-retryable status (501) yields retryable=false (derived from RetryUtils),
        // so the step must pass the failure through without retrying.
        val nonRetryable =
            object : HttpException(
                status = Status.NOT_IMPLEMENTED,
                headers = Headers.builder().build(),
                body = null,
            ) {}
        val client = FakeClient()
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val outcome = ResponseOutcome.Failure(nonRetryable)
        val out = step.invoke(outcome)
        assertSame(outcome, out)
        assertTrue(client.calls.isEmpty())
    }

    // endregion

    // region -- per-attempt retry-count header --

    @Test
    fun `attempt header is absent by default`() {
        // Opt-in feature: with the default settings no attempt header is stamped on retries.
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Ok(response(SC_OK)),
                ),
            )
        val step = RetryStep(client, zeroDelaySettings(InstantScheduler()), requestGet())
        val out = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(out is ResponseOutcome.Success)
        // Two retried sends were made; neither carries the attempt header.
        assertEquals(2, client.calls.size)
        client.calls.forEach { call ->
            assertNull(call.headers.get(DEFAULT_ATTEMPT_HEADER), "no attempt header should be stamped by default")
        }
    }

    @Test
    fun `attempt header is stamped with the attempt ordinal on each send via attempt()`() {
        // attempt() drives the initial send too, so we can observe the full ordinal sequence:
        // the original send is attempt 1, the first retry is 2, the second retry is 3.
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Ok(response(SC_OK)),
                ),
            )
        val settings =
            zeroDelaySettings(InstantScheduler())
                .newBuilder()
                .attemptHeaderName(DEFAULT_ATTEMPT_HEADER)
                .build()
        val step = RetryStep(client, settings, requestGet())

        val response = step.attempt()
        assertEquals(SC_OK, response.status.code)
        assertEquals(3, client.calls.size)
        assertEquals("1", client.calls[0].headers.get(DEFAULT_ATTEMPT_HEADER))
        assertEquals("2", client.calls[1].headers.get(DEFAULT_ATTEMPT_HEADER))
        assertEquals("3", client.calls[2].headers.get(DEFAULT_ATTEMPT_HEADER))
    }

    @Test
    fun `attempt header uses the configured header name`() {
        val custom = HttpHeaderName.fromString("X-My-Retry-Count")
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Ok(response(SC_OK)),
                ),
            )
        val settings =
            zeroDelaySettings(InstantScheduler())
                .newBuilder()
                .attemptHeaderName(custom)
                .build()
        val step = RetryStep(client, settings, requestGet())

        val out = step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        assertTrue(out is ResponseOutcome.Success)
        // invoke() does not control the external initial send (ordinal 1), so the retried sends
        // it dispatches start at ordinal 2. Two retries fire here under the configured name.
        assertEquals(2, client.calls.size)
        assertEquals("2", client.calls[0].headers.get(custom))
        assertEquals("3", client.calls[1].headers.get(custom))
    }

    @Test
    fun `attempt header is stamped on a per-attempt copy and does not mutate the template request`() {
        val client =
            FakeClient(
                listOf(
                    Canned.Err(httpException(SC_SERVICE_UNAVAILABLE)),
                    Canned.Ok(response(SC_OK)),
                ),
            )
        val request = requestGet()
        val settings =
            zeroDelaySettings(InstantScheduler())
                .newBuilder()
                .attemptHeaderName(DEFAULT_ATTEMPT_HEADER)
                .build()
        val step = RetryStep(client, settings, request)

        step.invoke(ResponseOutcome.Failure(httpException(SC_SERVICE_UNAVAILABLE)))
        // The retried request copy carries the header; the immutable template never gains it.
        assertEquals("2", client.calls[0].headers.get(DEFAULT_ATTEMPT_HEADER))
        assertNull(request.headers.get(DEFAULT_ATTEMPT_HEADER), "template request must stay unmodified")
    }

    // endregion

    private companion object {
        // HTTP status code constants for tests.
        private const val SC_OK = 200
        private const val SC_NOT_FOUND = 404
        private const val SC_SERVICE_UNAVAILABLE = 503

        // Header used by the per-attempt retry-count tests.
        private val DEFAULT_ATTEMPT_HEADER: HttpHeaderName = HttpHeaderName.fromString("X-Retry-Attempt")

        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_MAX_ATTEMPTS_TIMEOUT = 3

        // Interrupt-test timings — choosing modest values keeps the suite quick while still
        // giving the worker thread plenty of time to reach the get() call.
        private const val INTERRUPT_DELAY_MS = 50L
        private const val INTERRUPT_JOIN_TIMEOUT_MS = 2000L
    }
}
