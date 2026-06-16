/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.FakeSlf4jLogger
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.DateTimeRfc1123
import org.dexpace.sdk.io.OkioIoProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class RetryStepTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun tearDown() {
        // Clear interrupt status in case an interrupt test left it set.
        Thread.interrupted()
    }

    // ----------------- Type-level invariants -----------------

    @Test
    fun `stage is RETRY and final`() {
        val step = DefaultRetryStep()
        assertEquals(Stage.RETRY, step.stage)

        val custom =
            object : RetryStep() {
                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response = next.process()
            }
        assertEquals(Stage.RETRY, custom.stage)
    }

    // ----------------- maxRetries semantics -----------------

    @Test
    fun `maxRetries = 0 performs exactly one attempt and no retries`() {
        val fake = FakeHttpClient().enqueue { status(503) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 0), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `first attempt succeeds returns immediately`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `503 then 200 retries exactly once`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `three consecutive 503s with maxRetries=3 returns last 503 with 4 calls`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        assertEquals(4, fake.callCount)
    }

    // ----------------- Default classifier (status codes) -----------------

    @Test
    fun `408 is retryable`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(408) }
                .enqueue { status(200) }

        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `429 is retryable`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(429) }
                .enqueue { status(200) }

        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `501 is NOT retryable and is returned as-is`() {
        val fake = FakeHttpClient().enqueue { status(501) }
        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(501, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `505 is NOT retryable and is returned as-is`() {
        val fake = FakeHttpClient().enqueue { status(505) }
        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(505, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `200 is not retryable`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(1, fake.callCount)
    }

    // ----------------- Retry-After parsing -----------------

    @Test
    fun `Retry-After seconds drives exact delay`() {
        val clock = FixedClock()
        val before = clock.now()
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "5") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(5), Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After RFC 1123 date drives delay relative to clock now`() {
        // Use a fixed clock anchored on a known instant so DateTimeRfc1123.format is deterministic.
        val clock = FixedClock(Instant.parse("2024-06-01T12:00:00Z"))
        val target = clock.now().plusSeconds(10)
        val rfc1123 = DateTimeRfc1123.format(target)

        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", rfc1123) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(10), Duration.between(before, clock.now()))
    }

    @Test
    fun `retry-after-ms parses millisecond delay`() {
        val clock = FixedClock()
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("retry-after-ms", "1500") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofMillis(1500), Duration.between(before, clock.now()))
    }

    @Test
    fun `x-ms-retry-after-ms parses millisecond delay`() {
        val clock = FixedClock()
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("x-ms-retry-after-ms", "2000") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofMillis(2000), Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After 0 yields immediate retry`() {
        val clock = FixedClock()
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "0") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        // Zero-or-negative durations short-circuit the sleep path entirely.
        assertEquals(Duration.ZERO, Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After negative falls back to default backoff`() {
        val clock = FixedClock()
        // Configure a deterministic backoff: 100ms base with 100ms cap removes the
        // exponential growth window and the jitter range is at most 5ms.
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofMillis(100),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "-1") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        // 100ms ± 5ms jitter — Duration#compareTo lets us range-check.
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(95), "elapsed=$elapsed below 95ms")
        assertTrue(elapsed <= Duration.ofMillis(105), "elapsed=$elapsed above 105ms")
    }

    @Test
    fun `Retry-After non-numeric falls back to default backoff`() {
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofMillis(100),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "not-a-number") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(95))
        assertTrue(elapsed <= Duration.ofMillis(105))
    }

    @Test
    fun `custom retryAfterHeaders list ignores non-listed variants`() {
        val clock = FixedClock()
        // Only listen to the standard `Retry-After`; the `retry-after-ms` header is
        // therefore invisible and we fall back to backoff (100ms ± 5ms).
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofMillis(100),
                retryAfterHeaders = listOf(HttpHeaderName.RETRY_AFTER),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("retry-after-ms", "1500") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(95), "elapsed=$elapsed below 95ms")
        assertTrue(elapsed <= Duration.ofMillis(105), "elapsed=$elapsed above 105ms")
    }

    // ----------------- Exception classifier -----------------

    @Test
    fun `IOException is retried via exception classifier`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw IOException("transient")
                    return okResponse(request)
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `TimeoutException is retried`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw TimeoutException("slow")
                    return okResponse(request)
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `nested cause chain RuntimeException-of-IOException is retried`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw RuntimeException("wrapper", IOException("transient"))
                    return okResponse(request)
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `RuntimeException with no relevant cause is NOT retried`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw RuntimeException("unrelated")
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        // `process()` is declared `@Throws(IOException::class)`. A non-IO terminal failure
        // is wrapped so Java callers' `catch (IOException)` is honored; the original is
        // attached as cause.
        val wrapped = assertFailsWith<IOException> { pipeline.send(getRequest()) }
        assertTrue(wrapped.cause is RuntimeException, "expected RuntimeException cause")
        assertEquals("unrelated", wrapped.cause?.message)
        assertEquals(1, attempts.get())
    }

    // ----------------- Interrupt handling -----------------

    @Test
    fun `InterruptedException during sleep throws InterruptedIOException without retrying`() {
        val attempts = AtomicInteger(0)
        val interruptingClock =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH

                override fun monotonic(): Long = 0L

                override fun sleep(duration: Duration) {
                    throw InterruptedException("simulated interrupt")
                }
            }
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw IOException("transient")
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 5), interruptingClock))
                .build()

        val ex = assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        assertTrue(ex.cause is InterruptedException, "expected InterruptedException as cause")
        assertTrue(Thread.currentThread().isInterrupted, "interrupt status must be preserved")
        // Only the original attempt ran; the interrupt aborted the loop before a second call.
        assertEquals(1, attempts.get())
        // Clear status for AfterTest contract.
        Thread.interrupted()
    }

    @Test
    fun `interrupt during sleep preserves the current attempt's exception as suppressed`() {
        // The retry loop must record the current attempt's exception BEFORE sleeping so an
        // interrupt during sleep doesn't silently drop it. The InterruptedIOException must
        // carry every accumulated failure, including the one whose backoff was interrupted.
        val attempts = AtomicInteger(0)
        val interruptingClock =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH

                override fun monotonic(): Long = 0L

                override fun sleep(duration: Duration) {
                    throw InterruptedException("simulated interrupt")
                }
            }
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    throw IOException("attempt $n")
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 5), interruptingClock))
                .build()

        val ex = assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        // The current attempt's exception is in `suppressed` — without the fix it would be
        // silently dropped.
        assertTrue(
            ex.suppressed.any { it.message == "attempt 1" },
            "interrupted retry must preserve the current attempt's exception, got: ${ex.suppressed.toList()}",
        )
        Thread.interrupted()
    }

    @Test
    fun `InterruptedIOException from the transport is rethrown immediately without retry`() {
        // Per SDK cancellation convention, InterruptedIOException must not be classified as
        // retryable — the loop re-interrupts the thread and rethrows. Without the check the
        // exception classifier would see "IOException" and retry, masking cancellation.
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw InterruptedIOException("transport interrupted")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        assertEquals(1, attempts.get(), "InterruptedIOException must NOT be retried")
        assertTrue(Thread.currentThread().isInterrupted, "interrupt status must be set")
        Thread.interrupted()
    }

    @Test
    fun `bare InterruptedException from downstream restores the flag and surfaces as InterruptedIOException`() {
        // C-6: a raw java.lang.InterruptedException (not wrapped as InterruptedIOException) must
        // be treated as cancellation — the interrupt flag is restored and it is surfaced as
        // InterruptedIOException so the @Throws(IOException) contract holds. Without the fix it
        // would fall through to the terminal path, be wrapped as a plain IOException, and the
        // interrupt signal would be swallowed.
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw InterruptedException("downstream interrupted")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val ex = assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        assertTrue(ex.cause is InterruptedException, "original InterruptedException must be the cause")
        assertTrue(Thread.currentThread().isInterrupted, "interrupt status must be restored")
        assertEquals(1, attempts.get(), "InterruptedException must NOT be retried")
        Thread.interrupted()
    }

    @Test
    fun `bare InterruptedException attaches prior failures as suppressed`() {
        // After a prior retryable failure, a bare InterruptedException on the next attempt must
        // surface as InterruptedIOException carrying the earlier failure as suppressed.
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n == 1) throw IOException("transient first")
                    throw InterruptedException("interrupted on retry")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val ex = assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        assertTrue(
            ex.suppressed.any { it.message == "transient first" },
            "prior failure must be attached as suppressed, got: ${ex.suppressed.toList()}",
        )
        Thread.interrupted()
    }

    // ----------------- Body replayability gate (R-1) -----------------

    @Test
    fun `non-replayable POST body is NOT retried on a retryable response`() {
        // R-1: a non-idempotent method (POST) with a single-use body must not be retried — the
        // second writeTo would trip the consume-once guard. The 503 is returned as-is.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val response = pipeline.send(nonReplayablePost())
        assertEquals(503, response.status.code)
        assertEquals(1, fake.callCount, "non-replayable POST must not be retried")
    }

    @Test
    fun `non-replayable POST body is NOT retried on a retryable exception`() {
        // Same gate on the exception path: a transient IOException on a non-replayable POST is
        // rethrown without a second attempt (and without IllegalStateException masking).
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw IOException("transient")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IOException> { pipeline.send(nonReplayablePost()) }
        assertEquals("transient", ex.message, "the real IOException must surface, not a wrapped one")
        assertEquals(1, attempts.get(), "non-replayable POST must not be retried on exception")
    }

    @Test
    fun `replayable POST body IS retried on a retryable response`() {
        // Control: a replayable body on a POST is safe to re-send, so retry proceeds normally.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .body(org.dexpace.sdk.core.http.request.RequestBody.create("payload".toByteArray()))
                .build()

        val response = pipeline.send(request)
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "replayable POST must retry")
    }

    @Test
    fun `body-less POST is NOT retried on a retryable response`() {
        // Body-less retry safety keys off METHOD idempotency, not off the absence of a body. A
        // bare POST (no payload to re-send) is non-idempotent, so it must NOT be retried even on a
        // retryable status — a second POST could duplicate a side effect the server already
        // applied. The 503 is returned as-is after exactly one attempt. This exercises the
        // body == null branch of isRetrySafe on a non-idempotent method. Mirrored by the
        // `body-less POST is not retried` case in the pipeline.step.retry RetryStep suite.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) } // must never be reached

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .build()

        val response = pipeline.send(request)
        assertEquals(503, response.status.code)
        assertEquals(1, fake.callCount, "body-less POST must not be retried — POST is non-idempotent")
    }

    @Test
    fun `body-less PUT IS retried because PUT is idempotent`() {
        // Control for the body == null branch: with no body the gate falls through to method
        // idempotency. PUT is idempotent, so a body-less PUT is retry-safe and retries normally.
        // Mirrored by the `body-less PUT is retried` case in the pipeline.step.retry RetryStep suite.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.PUT)
                .url("https://api.example.com/x")
                .build()

        val response = pipeline.send(request)
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "body-less PUT must retry — PUT is idempotent")
    }

    @Test
    fun `non-replayable PUT body is NOT retried even though PUT is idempotent`() {
        // Both gates are required: PUT is idempotent, but a non-replayable body physically
        // cannot be re-sent, so the request is NOT retry-safe. The 503 is returned as-is rather
        // than re-dispatched (which would trip the body's consume-once guard on the second
        // writeTo). Method idempotency alone does not widen eligibility for a body-bearing
        // request.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.PUT)
                .url("https://api.example.com/x")
                .body(NonReplayableRequestBody())
                .build()

        val response = pipeline.send(request)
        assertEquals(503, response.status.code)
        assertEquals(1, fake.callCount, "non-replayable PUT must not be retried")
    }

    @Test
    fun `replayable PUT body IS retried`() {
        // Control for the gate: a replayable body on an idempotent PUT is safe to re-send, so
        // retry proceeds normally.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.PUT)
                .url("https://api.example.com/x")
                .body(org.dexpace.sdk.core.http.request.RequestBody.create("payload".toByteArray()))
                .build()

        val response = pipeline.send(request)
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "replayable PUT must retry")
    }

    @Test
    fun `real guarded one-shot PUT body surfaces the original 503 with no IllegalStateException`() {
        // End-to-end proof of the gate using a REAL consume-once body (RequestBody.create over a
        // non-markable InputStream → OneShotInputStreamRequestBody). The transport actually
        // drains the body on every call, so a buggy gate that retried a PUT on method-idempotency
        // alone would invoke writeTo a SECOND time and trip the consume-once CAS guard, surfacing
        // an IllegalStateException that masks the real failure. With both gates required the body
        // is written exactly once and the original 503 is returned verbatim.
        val writes = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    // Drain the body exactly as a real transport would — this exercises the
                    // consume-once guard.
                    request.body?.writeTo(Io.provider.buffer())
                    writes.incrementAndGet()
                    return Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(503))
                        .headers(Headers.Builder().build())
                        .build()
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        val request =
            Request.builder()
                .method(Method.PUT)
                .url("https://api.example.com/x")
                .body(oneShotBody())
                .build()

        val response = pipeline.send(request)
        assertEquals(503, response.status.code, "original 503 must be surfaced, not an IllegalStateException")
        assertEquals(1, writes.get(), "the one-shot body must be written exactly once — no replay")
    }

    // ----------------- Accumulated suppressed exceptions -----------------

    @Test
    fun `accumulated prior exceptions are addSuppressed on final failure`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    throw IOException("attempt $n")
                }
            }

        // maxRetries = 2 → initial + 2 retries = 3 attempts. The third (final) exception
        // is rethrown with the prior two attached as suppressed.
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 2), zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IOException> { pipeline.send(getRequest()) }
        assertEquals("attempt 3", ex.message)
        assertEquals(2, ex.suppressed.size)
        assertEquals("attempt 1", ex.suppressed[0].message)
        assertEquals("attempt 2", ex.suppressed[1].message)
        assertEquals(3, attempts.get())
    }

    // ----------------- Fixed-delay strategy -----------------

    @Test
    fun `fixedDelay uses a flat duration with no exponential growth`() {
        val clock = FixedClock()
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(200) }

        val opts = HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofMillis(100))
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        // Two retries × 100ms each = 200ms total, no jitter.
        assertEquals(Duration.ofMillis(200), Duration.between(before, clock.now()))
    }

    @Test
    fun `exponential backoff grows roughly 100 200 400 with 5pct jitter`() {
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                maxRetries = 3,
                baseDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofSeconds(1),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        // Expected: 100 + 200 + 400 = 700ms, ± 5% on each → ± 35ms total.
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(665), "elapsed=$elapsed below lower bound")
        assertTrue(elapsed <= Duration.ofMillis(735), "elapsed=$elapsed above upper bound")
    }

    @Test
    fun `jitter stays within +- 5 percent across many samples`() {
        // Drive the same try count repeatedly through the step and check the spread is
        // within the documented bound. Run via a small reflective-ish hack: invoke the
        // backoff path indirectly by sending many one-retry sequences and reading the
        // FixedClock delta.
        val baseMs = 1000L
        val opts =
            HttpRetryOptions(
                maxRetries = 1,
                baseDelay = Duration.ofMillis(baseMs),
                maxDelay = Duration.ofMillis(baseMs),
            )
        val samples = ArrayList<Long>(1000)
        repeat(1000) {
            val clock = FixedClock()
            val fake =
                FakeHttpClient()
                    .enqueue { status(503) }
                    .enqueue { status(200) }
            val pipeline =
                HttpPipelineBuilder(fake)
                    .append(DefaultRetryStep(opts, clock))
                    .build()
            val before = clock.now()
            pipeline.send(getRequest())
            samples.add(Duration.between(before, clock.now()).toMillis())
        }
        // 5% of 1000ms is 50ms.
        for (ms in samples) {
            assertTrue(ms in 950..1050, "sample $ms outside [950,1050]")
        }
        // Distribution sanity: at least 10% of samples should be on either side of the
        // mean to confirm the jitter is non-trivially applied. Tight assertion to catch
        // an accidental zero-jitter regression.
        val below = samples.count { it < baseMs }
        val above = samples.count { it > baseMs }
        assertTrue(below > 100, "expected >=10pct samples below mean, got $below")
        assertTrue(above > 100, "expected >=10pct samples above mean, got $above")
    }

    // ----------------- Custom hooks -----------------

    @Test
    fun `delayFromCondition override returns exact delay verbatim`() {
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                maxRetries = 1,
                delayFromCondition = { Duration.ofSeconds(7) },
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(7), Duration.between(before, clock.now()))
    }

    @Test
    fun `custom shouldRetryCondition is consulted and overrides default`() {
        val invocations = AtomicInteger(0)
        val opts =
            HttpRetryOptions(
                maxRetries = 3,
                shouldRetryCondition = { c ->
                    invocations.incrementAndGet()
                    // Only retry while we have a response and we haven't retried yet.
                    c.response != null && c.tryCount == 0
                },
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        // Original + one retry → 2 calls. Predicate called twice (tryCount 0, then 1).
        assertEquals(503, response.status.code)
        assertEquals(2, fake.callCount)
        assertEquals(2, invocations.get())
    }

    @Test
    fun `shouldRetryCondition that throws is wrapped in IllegalStateException`() {
        val opts =
            HttpRetryOptions(
                shouldRetryCondition = { throw RuntimeException("predicate boom") },
            )
        val fake = FakeHttpClient().enqueue { status(503) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getRequest()) }
        assertTrue(ex.message?.contains("shouldRetry predicate threw") == true)
        assertTrue(ex.cause?.message == "predicate boom")
    }

    @Test
    fun `throwing shouldRetryCondition closes the retryable response before propagating`() {
        // M6: the should-retry predicate runs while the retryable response is still open. If it
        // throws, the response must be closed before the exception propagates — otherwise the
        // 5xx response's socket/buffer leaks. Assert both the close() is observed and the
        // exception still surfaces.
        val closes = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response =
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(503))
                        .headers(Headers.Builder().build())
                        .body(CountingClosableBody(closes))
                        .build()
            }
        val opts =
            HttpRetryOptions(
                shouldRetryCondition = { throw RuntimeException("predicate boom") },
            )
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getRequest()) }
        assertTrue(ex.message?.contains("shouldRetry predicate threw") == true)
        assertTrue(closes.get() >= 1, "retryable response must be closed when the predicate throws")
    }

    @Test
    fun `throwing computeResponseDelay override closes the retryable response before propagating`() {
        // M6: a subclass override of computeResponseDelay runs after the predicate approves the
        // retry, while the response is still open. If it throws, the response must be closed
        // before the exception propagates.
        val closes = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response =
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(503))
                        .headers(Headers.Builder().build())
                        .body(CountingClosableBody(closes))
                        .build()
            }
        val step =
            object : DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()) {
                override fun computeResponseDelay(condition: HttpRetryCondition): Duration =
                    error("delay override boom")
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(step)
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getRequest()) }
        assertEquals("delay override boom", ex.message)
        assertTrue(closes.get() >= 1, "retryable response must be closed when the delay override throws")
    }

    // ----------------- Error propagation -----------------

    @Test
    fun `OutOfMemoryError propagates immediately with no retry`() {
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    attempts.incrementAndGet()
                    throw OutOfMemoryError("simulated")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .build()

        assertFailsWith<OutOfMemoryError> { pipeline.send(getRequest()) }
        assertEquals(1, attempts.get())
    }

    // ----------------- next.copy() per attempt -----------------

    @Test
    fun `next-dot-copy is invoked exactly once per attempt`() {
        val nextSeen = ArrayList<PipelineNext>()
        val recorder =
            object : HttpStep {
                override val stage: Stage = Stage.POST_RETRY

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response {
                    nextSeen.add(next)
                    return next.process()
                }
            }

        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
                .append(recorder)
                .build()

        pipeline.send(getRequest())

        assertEquals(3, nextSeen.size, "downstream step should be invoked once per attempt")
        // Each PipelineNext is a fresh instance from next.copy() — never the same object.
        for (i in 1 until nextSeen.size) {
            assertTrue(nextSeen[i] !== nextSeen[i - 1], "next must be copied per attempt")
        }
    }

    // ----------------- response.close() before retry -----------------

    @Test
    fun `prior response is closed before sleeping`() {
        val closes = AtomicInteger(0)
        val callCount = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = callCount.incrementAndGet()
                    val statusCode = if (n == 1) 503 else 200
                    return Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(statusCode))
                        .headers(Headers.Builder().build())
                        .body(CountingClosableBody(closes))
                        .build()
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        pipeline.send(getRequest())
        // The 503 response's body should have been closed before we slept and retried.
        assertTrue(closes.get() >= 1, "expected the failed response body to be closed")
    }

    // ----------------- Retry-After: parse failures and header variants -----------------

    @Test
    fun `Retry-After RFC 1123 date in the past yields immediate retry`() {
        // RFC 7231 allows `Retry-After: <http-date>`. A date already in the past must be
        // treated as zero — implementations that compute `Duration.between(now, past)`
        // would otherwise produce a negative duration that the sleep step rejects.
        val clock = FixedClock(Instant.parse("2024-06-01T12:00:00Z"))
        val past = clock.now().minusSeconds(60)
        val rfc1123 = DateTimeRfc1123.format(past)
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", rfc1123) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ZERO, Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After malformed date falls back to default backoff`() {
        // Not parseable as either integer seconds or RFC 1123 — drops to backoff.
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofMillis(100),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "not a date, not a number") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        // Within the 5% jitter window of 100ms.
        assertTrue(elapsed >= Duration.ofMillis(95), "elapsed=$elapsed below 95ms")
        assertTrue(elapsed <= Duration.ofMillis(105), "elapsed=$elapsed above 105ms")
    }

    @Test
    fun `Retry-After negative milliseconds is rejected and falls back to backoff`() {
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(50),
                maxDelay = Duration.ofMillis(50),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("retry-after-ms", "-1000") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(45), "elapsed=$elapsed below 45ms")
        assertTrue(elapsed <= Duration.ofMillis(55), "elapsed=$elapsed above 55ms")
    }

    @Test
    fun `Retry-After very large value below the clamp ceiling is respected`() {
        // Numeric `Retry-After` values below the parser's 365-day clamp ceiling are honored
        // verbatim. 3601 seconds is just over an hour — well under the ceiling — so the full
        // delay is applied with no clamping.
        val clock = FixedClock()
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "3601") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(), clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(3601), Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After empty value falls back to default backoff`() {
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(80),
                maxDelay = Duration.ofMillis(80),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("Retry-After", "   ") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(75), "elapsed=$elapsed below 75ms")
        assertTrue(elapsed <= Duration.ofMillis(85), "elapsed=$elapsed above 85ms")
    }

    // ----------------- Exception-side hook coverage -----------------

    @Test
    fun `delayFromCondition is consulted on the exception path`() {
        // The retry loop computes the delay differently for exception vs response paths.
        // Cover the exception-path branch through `computeExceptionDelay` which skips the
        // Retry-After parsing step.
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw IOException("transient")
                    return okResponse(request)
                }
            }
        val opts =
            HttpRetryOptions(
                maxRetries = 3,
                delayFromCondition = { Duration.ofSeconds(2) },
            )
        val clock = FixedClock()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(2), Duration.between(before, clock.now()))
    }

    @Test
    fun `delayFromCondition override that throws falls back to default delay`() {
        // The step swallows a buggy override and logs at warning. Verifies the fallback
        // continues to retry with the backoff path rather than aborting the loop.
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                maxRetries = 2,
                baseDelay = Duration.ofMillis(50),
                maxDelay = Duration.ofMillis(50),
                delayFromCondition = { throw RuntimeException("buggy override") },
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ofMillis(45), "elapsed=$elapsed below 45ms")
        assertTrue(elapsed <= Duration.ofMillis(55), "elapsed=$elapsed above 55ms")
    }

    @Test
    fun `custom shouldRetryException is consulted and overrides default`() {
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response = throw RuntimeException("no retry classifier match")
            }
        val invocations = AtomicInteger(0)
        val opts =
            HttpRetryOptions(
                maxRetries = 2,
                shouldRetryException = {
                    invocations.incrementAndGet()
                    // Force retry on any exception even though the default would not.
                    true
                },
            )
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        // Non-IO terminal failure is wrapped as `IOException` (see `RuntimeException with no
        // relevant cause is NOT retried`); the original is attached as cause.
        val wrapped = assertFailsWith<IOException> { pipeline.send(getRequest()) }
        assertTrue(wrapped.cause is RuntimeException)
        // Initial + 2 retries → 2 calls to the exception predicate (after attempt 0 and 1).
        assertEquals(2, invocations.get(), "exception predicate must be consulted on each retry decision")
    }

    @Test
    fun `shouldRetryException that throws is wrapped in IllegalStateException`() {
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response = throw IOException("transient")
            }
        val opts =
            HttpRetryOptions(
                shouldRetryException = { throw RuntimeException("predicate boom") },
            )
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getRequest()) }
        assertTrue(ex.message?.contains("shouldRetry predicate threw") == true)
    }

    // ----------------- Custom retry classifier - status 503 only -----------------

    @Test
    fun `custom shouldRetryCondition gating on status 503 only`() {
        // Retry only when the status is exactly 503. A 429 (normally retryable) must NOT
        // be retried — the custom predicate fully replaces the default classifier.
        val fake =
            FakeHttpClient()
                .enqueue { status(429) } // default would retry; predicate refuses

        val opts =
            HttpRetryOptions(
                maxRetries = 3,
                shouldRetryCondition = { c -> c.response?.status?.code == 503 },
            )
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(429, response.status.code)
        assertEquals(1, fake.callCount, "custom predicate must veto retry on non-503")
    }

    // ----------------- Error propagation across retry path -----------------

    @Test
    fun `Error from the predicate propagates without IllegalStateException wrapping`() {
        // The invokeShouldRetry helper specifically rethrows Error subclasses without
        // wrapping. An OOM in the predicate must surface unchanged for the JVM crash signal.
        val fake = FakeHttpClient().enqueue { status(503) }
        val opts =
            HttpRetryOptions(
                shouldRetryCondition = { throw OutOfMemoryError("simulated in predicate") },
            )
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        assertFailsWith<OutOfMemoryError> { pipeline.send(getRequest()) }
    }

    @Test
    fun `Error from delayFromCondition propagates without wrapping`() {
        // Same contract as above but for the delay hook — Error subclasses bypass the
        // generic catch-and-fallback path.
        val fake = FakeHttpClient().enqueue { status(503) }
        val opts =
            HttpRetryOptions(
                delayFromCondition = { throw OutOfMemoryError("OOM in delay hook") },
            )
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, zeroDelayClock()))
                .build()

        assertFailsWith<OutOfMemoryError> { pipeline.send(getRequest()) }
    }

    // ----------------- Suppressed exception accumulation -----------------

    @Test
    fun `three failed attempts attach two suppressed exceptions on final failure`() {
        // 3 failures total = initial + 2 retries; final exception has 2 suppressed prior
        // exceptions (the third one is the rethrown one itself).
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    throw IOException("attempt $n")
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 2), zeroDelayClock()))
                .build()

        val ex = assertFailsWith<IOException> { pipeline.send(getRequest()) }
        assertEquals("attempt 3", ex.message)
        assertEquals(2, ex.suppressed.size, "expected 2 prior failures suppressed")
        assertEquals("attempt 1", ex.suppressed[0].message)
        assertEquals("attempt 2", ex.suppressed[1].message)
    }

    // ----------------- MAX_SHIFT_TRY_COUNT cap -----------------

    @Test
    fun `baseDelay zero produces an immediate retry through the early-return branch`() {
        // `if (baseNanos == 0L) return Duration.ZERO` in exponentialBackoff. The fixed-delay
        // tests already drive baseDelay=Duration.ZERO, but this one explicitly exercises the
        // exponential path with a zero base — the branch must short-circuit before any shift.
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                maxRetries = 1,
                baseDelay = Duration.ZERO,
                maxDelay = Duration.ofMillis(100),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ZERO, Duration.between(before, clock.now()))
    }

    @Test
    fun `exponential backoff overflow path is clamped to maxDelay rather than wrapping negative`() {
        // Drive the overflow check: with baseDelay near Long.MAX_VALUE/2 and tryCount=30,
        // baseNanos * (1L shl 30) overflows. The step must clamp to Long.MAX_VALUE and then
        // to maxDelay rather than returning a negative duration.
        val clock = FixedClock()
        // 9_223_372_036 seconds is well over Long.MAX_VALUE / 2^30 in nanos, forcing the
        // overflow branch.
        val opts =
            HttpRetryOptions(
                maxRetries = 35,
                baseDelay = Duration.ofSeconds(9_223_372_036L),
                maxDelay = Duration.ofMillis(2),
            )
        val fake = FakeHttpClient()
        repeat(36) { fake.enqueue { status(503) } }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        // Each retry clamped to maxDelay = 2ms; total ≤ 35 × 2ms = 70ms plus jitter.
        val elapsed = Duration.between(before, clock.now())
        assertTrue(elapsed >= Duration.ZERO, "elapsed=$elapsed went negative — overflow regression")
        assertTrue(elapsed <= Duration.ofMillis(200), "elapsed=$elapsed exceeds clamped maxDelay")
    }

    @Test
    fun `tryCount over 30 is capped for the shift operation`() {
        // The step caps `1L shl tryCount` at 1L shl 30 to avoid overflow. With baseDelay=1ms
        // and maxRetries=35, repeated retries should never produce a negative or absurdly
        // huge delay even though tryCount eventually exceeds 30. The maxDelay clamps the
        // result; we verify the loop completes in bounded total time.
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                maxRetries = 35,
                baseDelay = Duration.ofNanos(1),
                maxDelay = Duration.ofMillis(1),
            )
        // 35 retries × ~1ms each = ~35ms upper bound; pre-fill 36 enqueued responses.
        val fake = FakeHttpClient()
        repeat(36) { fake.enqueue { status(503) } }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        assertEquals(36, fake.callCount, "initial + 35 retries")
        val elapsed = Duration.between(before, clock.now())
        // Each delay clamped to ≤ 1ms; total upper bound is well under 200ms even with jitter.
        assertTrue(elapsed <= Duration.ofMillis(200), "elapsed=$elapsed grew unbounded — shift cap regressed")
    }

    // ----------------- Custom retryAfterHeaders list with single entry -----------------

    @Test
    fun `retryAfterHeaders restricted to RETRY_AFTER ignores ms variants`() {
        // Identical to the existing test but uses a singleton list that explicitly excludes
        // both ms variants — confirms the list-walk order matters and unsupported names are
        // ignored, not parsed.
        val clock = FixedClock()
        val opts =
            HttpRetryOptions(
                baseDelay = Duration.ofMillis(50),
                maxDelay = Duration.ofMillis(50),
                retryAfterHeaders = listOf(HttpHeaderName.RETRY_AFTER),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("x-ms-retry-after-ms", "3000") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        val elapsed = Duration.between(before, clock.now())
        // Default backoff bounds, not the 3000ms the server hinted at.
        assertTrue(elapsed >= Duration.ofMillis(45), "elapsed=$elapsed below 45ms")
        assertTrue(elapsed <= Duration.ofMillis(55), "elapsed=$elapsed above 55ms")
    }

    @Test
    fun `retryAfterHeaders with non-default unknown name uses the millisecond fallback`() {
        // A header name that is neither `Retry-After` nor `X-RateLimit-Reset` falls through to
        // RetryAfterParser's millisecond default. Passing a non-built-in HttpHeaderName forces
        // that branch.
        val clock = FixedClock()
        val unknown = HttpHeaderName.fromString("X-Custom-Retry-After-Ms")
        val opts =
            HttpRetryOptions(
                retryAfterHeaders = listOf(unknown),
            )
        val fake =
            FakeHttpClient()
                .enqueue { status(503).header("X-Custom-Retry-After-Ms", "1234") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(opts, clock))
                .build()

        val before = clock.now()
        pipeline.send(getRequest())
        // Parsed as milliseconds: exactly 1234ms.
        assertEquals(Duration.ofMillis(1234), Duration.between(before, clock.now()))
    }

    // ----------------- close() before sleep on retry path -----------------

    @Test
    fun `response close() error is swallowed and the retry proceeds`() {
        // The step closes the previous response before sleeping. If close() throws an
        // IOException the loop must continue rather than aborting — surfacing a close error
        // on a soon-to-be-replaced response is not actionable.
        val callCount = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = callCount.incrementAndGet()
                    val statusCode = if (n == 1) 503 else 200
                    return Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(statusCode))
                        .headers(Headers.Builder().build())
                        .body(ThrowingCloseBody(n == 1))
                        .build()
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, callCount.get(), "loop must not abort on close() failure")
    }

    // ----------------- maxRetries clamping -----------------

    @Test
    fun `negative maxRetries is clamped to default 3`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) }
                .enqueue { status(503) } // extra in case clamp went wrong

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = -5), zeroDelayClock()))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        // Default is 3 retries → 1 original + 3 retries = 4 calls.
        assertEquals(4, fake.callCount)
    }

    // ----------------- Diagnostic field coverage -----------------

    @Test
    fun `retry on IOException emits retry_cause_class with exception simple name`() {
        val fakeSlf4j = FakeSlf4jLogger("test.retry")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)

        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw IOException("net")
                    return okResponse(request)
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock(), clientLogger))
                .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)

        val retryRecord =
            fakeSlf4j.records.first { rec ->
                rec.keyValues.any { it.key == "event" && it.value == "http.retry" }
            }
        val kv = retryRecord.keyValues.associate { it.key to it.value }
        assertEquals("IOException", kv["retry.cause_class"])
    }

    @Test
    fun `retry emits non-negative total_elapsed_ms`() {
        val fakeSlf4j = FakeSlf4jLogger("test.retry")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)

        val clock = FixedClock()
        val attempts = AtomicInteger(0)
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response {
                    val n = attempts.incrementAndGet()
                    if (n < 2) throw IOException("net")
                    return okResponse(request)
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep(HttpRetryOptions(), clock, clientLogger))
                .build()

        pipeline.send(getRequest())

        val retryRecord =
            fakeSlf4j.records.first { rec ->
                rec.keyValues.any { it.key == "event" && it.value == "http.retry" }
            }
        val kv = retryRecord.keyValues.associate { it.key to it.value }
        val elapsedMs = kv["retry.total_elapsed_ms"] as Long
        assertTrue(elapsedMs >= 0L, "retry.total_elapsed_ms must be non-negative; got $elapsedMs")
    }

    // ----------------- Helpers -----------------

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .build()

    /** A POST request carrying a single-use (non-replayable) body. */
    private fun nonReplayablePost(): Request =
        Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/x")
            .body(NonReplayableRequestBody())
            .build()

    /**
     * A REAL guarded one-shot body: `RequestBody.create(InputStream, length)` over a
     * non-markable stream yields a `OneShotInputStreamRequestBody` whose [writeTo] enforces a
     * consume-once CAS guard — a second write throws [IllegalStateException]. Used to prove a
     * non-replayable PUT body is never re-sent.
     */
    private fun oneShotBody(): org.dexpace.sdk.core.http.request.RequestBody {
        val backing = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val nonMarkable =
            object : InputStream() {
                override fun read(): Int = backing.read()

                override fun markSupported(): Boolean = false
            }
        return org.dexpace.sdk.core.http.request.RequestBody.create(nonMarkable, 3L)
    }

    /** Single-use request body: [isReplayable] is false and [writeTo] streams fixed bytes. */
    private class NonReplayableRequestBody : org.dexpace.sdk.core.http.request.RequestBody() {
        override fun mediaType(): MediaType? = MediaType.parse("text/plain")

        override fun contentLength(): Long = 5

        override fun isReplayable(): Boolean = false

        override fun writeTo(sink: org.dexpace.sdk.core.io.BufferedSink) {
            sink.write("hello".toByteArray(Charsets.UTF_8))
        }
    }

    private fun retryPipeline(client: HttpClient): org.dexpace.sdk.core.http.pipeline.HttpPipeline =
        HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
            .build()

    private fun okResponse(request: Request): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .headers(Headers.Builder().build())
            .build()

    /**
     * Clock whose [Clock.sleep] is a no-op (does not advance any clock face). Used for
     * tests that don't care about timing — keeps the assertions focused on call counts.
     */
    private fun zeroDelayClock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.EPOCH

            override fun monotonic(): Long = 0L

            override fun sleep(duration: Duration) { /* no-op */ }
        }

    /** Counts close() calls; throws if anyone tries to read source(). */
    private class CountingClosableBody(private val closes: AtomicInteger) : ResponseBody() {
        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = 0

        override fun source(): BufferedSource = fail("body should not be read in close-tracking test")

        override fun close() {
            closes.incrementAndGet()
        }
    }

    /** Body whose close() throws IOException — used to verify the step swallows the error. */
    private class ThrowingCloseBody(private val shouldThrow: Boolean) : ResponseBody() {
        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = 0

        override fun source(): BufferedSource = fail("body should not be read in close-tracking test")

        override fun close() {
            if (shouldThrow) throw IOException("simulated close failure")
        }
    }
}
