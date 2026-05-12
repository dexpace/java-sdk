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
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.DateTimeRfc1123
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
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

        val custom = object : RetryStep() {
            override fun process(request: Request, next: PipelineNext): Response = next.process()
        }
        assertEquals(Stage.RETRY, custom.stage)
    }

    // ----------------- maxRetries semantics -----------------

    @Test
    fun `maxRetries = 0 performs exactly one attempt and no retries`() {
        val fake = FakeHttpClient().enqueue { status(503) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 0), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `first attempt succeeds returns immediately`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `503 then 200 retries exactly once`() {
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `three consecutive 503s with maxRetries=3 returns last 503 with 4 calls`() {
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        assertEquals(4, fake.callCount)
    }

    // ----------------- Default classifier (status codes) -----------------

    @Test
    fun `408 is retryable`() {
        val fake = FakeHttpClient()
            .enqueue { status(408) }
            .enqueue { status(200) }

        val pipeline = retryPipeline(fake)
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `429 is retryable`() {
        val fake = FakeHttpClient()
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
        val fake = FakeHttpClient()
            .enqueue { status(503).header("Retry-After", "5") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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

        val fake = FakeHttpClient()
            .enqueue { status(503).header("Retry-After", rfc1123) }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(), clock))
            .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(10), Duration.between(before, clock.now()))
    }

    @Test
    fun `retry-after-ms parses millisecond delay`() {
        val clock = FixedClock()
        val fake = FakeHttpClient()
            .enqueue { status(503).header("retry-after-ms", "1500") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(), clock))
            .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofMillis(1500), Duration.between(before, clock.now()))
    }

    @Test
    fun `x-ms-retry-after-ms parses millisecond delay`() {
        val clock = FixedClock()
        val fake = FakeHttpClient()
            .enqueue { status(503).header("x-ms-retry-after-ms", "2000") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(), clock))
            .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofMillis(2000), Duration.between(before, clock.now()))
    }

    @Test
    fun `Retry-After 0 yields immediate retry`() {
        val clock = FixedClock()
        val fake = FakeHttpClient()
            .enqueue { status(503).header("Retry-After", "0") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            baseDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofMillis(100),
        )
        val fake = FakeHttpClient()
            .enqueue { status(503).header("Retry-After", "-1") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            baseDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofMillis(100),
        )
        val fake = FakeHttpClient()
            .enqueue { status(503).header("Retry-After", "not-a-number") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            baseDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofMillis(100),
            retryAfterHeaders = listOf(HttpHeaderName.RETRY_AFTER),
        )
        val fake = FakeHttpClient()
            .enqueue { status(503).header("retry-after-ms", "1500") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                val n = attempts.incrementAndGet()
                if (n < 2) throw IOException("transient")
                return okResponse(request)
            }
        }

        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `TimeoutException is retried`() {
        val attempts = AtomicInteger(0)
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                val n = attempts.incrementAndGet()
                if (n < 2) throw TimeoutException("slow")
                return okResponse(request)
            }
        }

        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `nested cause chain RuntimeException-of-IOException is retried`() {
        val attempts = AtomicInteger(0)
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                val n = attempts.incrementAndGet()
                if (n < 2) throw RuntimeException("wrapper", IOException("transient"))
                return okResponse(request)
            }
        }

        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `RuntimeException with no relevant cause is NOT retried`() {
        val attempts = AtomicInteger(0)
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                attempts.incrementAndGet()
                throw RuntimeException("unrelated")
            }
        }

        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        assertFailsWith<RuntimeException> { pipeline.send(getRequest()) }
        assertEquals(1, attempts.get())
    }

    // ----------------- Interrupt handling -----------------

    @Test
    fun `InterruptedException during sleep throws InterruptedIOException without retrying`() {
        val attempts = AtomicInteger(0)
        val interruptingClock = object : Clock {
            override fun now(): Instant = Instant.EPOCH
            override fun monotonic(): Long = 0L
            override fun sleep(duration: Duration) {
                throw InterruptedException("simulated interrupt")
            }
        }
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                attempts.incrementAndGet()
                throw IOException("transient")
            }
        }

        val pipeline = HttpPipelineBuilder(client)
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
        val interruptingClock = object : Clock {
            override fun now(): Instant = Instant.EPOCH
            override fun monotonic(): Long = 0L
            override fun sleep(duration: Duration) {
                throw InterruptedException("simulated interrupt")
            }
        }
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                val n = attempts.incrementAndGet()
                throw IOException("attempt $n")
            }
        }

        val pipeline = HttpPipelineBuilder(client)
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
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                attempts.incrementAndGet()
                throw InterruptedIOException("transport interrupted")
            }
        }
        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
            .build()

        assertFailsWith<InterruptedIOException> { pipeline.send(getRequest()) }
        assertEquals(1, attempts.get(), "InterruptedIOException must NOT be retried")
        assertTrue(Thread.currentThread().isInterrupted, "interrupt status must be set")
        Thread.interrupted()
    }

    // ----------------- Accumulated suppressed exceptions -----------------

    @Test
    fun `accumulated prior exceptions are addSuppressed on final failure`() {
        val attempts = AtomicInteger(0)
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                val n = attempts.incrementAndGet()
                throw IOException("attempt $n")
            }
        }

        // maxRetries = 2 → initial + 2 retries = 3 attempts. The third (final) exception
        // is rethrown with the prior two attached as suppressed.
        val pipeline = HttpPipelineBuilder(client)
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
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(200) }

        val opts = HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofMillis(100))
        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            maxRetries = 3,
            baseDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofSeconds(1),
        )
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            maxRetries = 1,
            baseDelay = Duration.ofMillis(baseMs),
            maxDelay = Duration.ofMillis(baseMs),
        )
        val samples = ArrayList<Long>(1000)
        repeat(1000) {
            val clock = FixedClock()
            val fake = FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200) }
            val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            maxRetries = 1,
            delayFromCondition = { Duration.ofSeconds(7) },
        )
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(opts, clock))
            .build()

        val before = clock.now()
        pipeline.send(getRequest())
        assertEquals(Duration.ofSeconds(7), Duration.between(before, clock.now()))
    }

    @Test
    fun `custom shouldRetryCondition is consulted and overrides default`() {
        val invocations = AtomicInteger(0)
        val opts = HttpRetryOptions(
            maxRetries = 3,
            shouldRetryCondition = { c ->
                invocations.incrementAndGet()
                // Only retry while we have a response and we haven't retried yet.
                c.response != null && c.tryCount == 0
            },
        )
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val opts = HttpRetryOptions(
            shouldRetryCondition = { throw RuntimeException("predicate boom") },
        )
        val fake = FakeHttpClient().enqueue { status(503) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(opts, zeroDelayClock()))
            .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getRequest()) }
        assertTrue(ex.message?.contains("shouldRetry predicate threw") == true)
        assertTrue(ex.cause?.message == "predicate boom")
    }

    // ----------------- Error propagation -----------------

    @Test
    fun `OutOfMemoryError propagates immediately with no retry`() {
        val attempts = AtomicInteger(0)
        val client = object : HttpClient {
            override fun execute(request: Request): Response {
                attempts.incrementAndGet()
                throw OutOfMemoryError("simulated")
            }
        }
        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3), zeroDelayClock()))
            .build()

        assertFailsWith<OutOfMemoryError> { pipeline.send(getRequest()) }
        assertEquals(1, attempts.get())
    }

    // ----------------- next.copy() per attempt -----------------

    @Test
    fun `next-dot-copy is invoked exactly once per attempt`() {
        val nextSeen = ArrayList<PipelineNext>()
        val recorder = object : HttpStep {
            override val stage: Stage = Stage.POST_RETRY
            override fun process(request: Request, next: PipelineNext): Response {
                nextSeen.add(next)
                return next.process()
            }
        }

        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
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
        val client = object : HttpClient {
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

        val pipeline = HttpPipelineBuilder(client)
            .append(DefaultRetryStep(HttpRetryOptions(), zeroDelayClock()))
            .build()

        pipeline.send(getRequest())
        // The 503 response's body should have been closed before we slept and retried.
        assertTrue(closes.get() >= 1, "expected the failed response body to be closed")
    }

    // ----------------- maxRetries clamping -----------------

    @Test
    fun `negative maxRetries is clamped to default 3`() {
        val fake = FakeHttpClient()
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) }
            .enqueue { status(503) } // extra in case clamp went wrong

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRetryStep(HttpRetryOptions(maxRetries = -5), zeroDelayClock()))
            .build()

        val response = pipeline.send(getRequest())
        assertEquals(503, response.status.code)
        // Default is 3 retries → 1 original + 3 retries = 4 calls.
        assertEquals(4, fake.callCount)
    }

    // ----------------- Helpers -----------------

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .build()

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
    private fun zeroDelayClock(): Clock = object : Clock {
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
}
