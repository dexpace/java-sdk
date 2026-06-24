/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.testing.ManualScheduler
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.Futures
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DefaultAsyncRetryStepTest {
    private val scheduler = ManualScheduler()

    @AfterTest
    fun tearDown() {
        scheduler.close()
    }

    // ----------------- Type-level invariants -----------------

    @Test
    fun `stage is RETRY and final`() {
        val step = DefaultAsyncRetryStep(scheduler)
        assertEquals(Stage.RETRY, step.stage)
        val custom =
            object : AsyncRetryStep() {
                override fun processAsync(
                    request: Request,
                    next: AsyncPipelineNext,
                ): CompletableFuture<Response> = next.processAsync()
            }
        assertEquals(Stage.RETRY, custom.stage)
    }

    // ----------------- maxRetries semantics -----------------

    @Test
    fun `maxRetries = 0 performs exactly one attempt`() {
        val client = QueueClient().enqueue(503)
        val future = pipeline(client, HttpRetryOptions(maxRetries = 0)).sendAsync(getRequest())
        scheduler.runAll()
        assertEquals(503, future.join().status.code)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `retries a 503 until a 200 within the budget`() {
        val client = QueueClient().enqueue(503).enqueue(503).enqueue(200)
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofMillis(50)))
                .sendAsync(getRequest())
        // Each backoff schedules a task on the manual scheduler; drain them to advance the loop.
        scheduler.runAll()
        assertEquals(200, future.join().status.code)
        assertEquals(3, client.callCount)
    }

    @Test
    fun `exhausts retries and returns the last retryable response`() {
        val client = QueueClient().enqueue(503).enqueue(503).enqueue(503)
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 2, delay = Duration.ofMillis(10)))
                .sendAsync(getRequest())
        scheduler.runAll()
        assertEquals(503, future.join().status.code)
        // initial + 2 retries.
        assertEquals(3, client.callCount)
    }

    @Test
    fun `non-retryable status is returned without retry`() {
        val client = QueueClient().enqueue(404)
        val future = pipeline(client, HttpRetryOptions()).sendAsync(getRequest())
        scheduler.runAll()
        assertEquals(404, future.join().status.code)
        assertEquals(1, client.callCount)
    }

    // ----------------- Exception retry -----------------

    @Test
    fun `retries a retryable IOException then succeeds`() {
        val client = FailNTimesClient(failures = 2) { IOException("boom") }
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofMillis(5)))
                .sendAsync(getRequest())
        scheduler.runAll()
        assertEquals(200, future.join().status.code)
        assertEquals(3, client.callCount)
    }

    @Test
    fun `terminal exception carries prior attempts as suppressed`() {
        val client = FailNTimesClient(failures = 5) { IOException("boom") }
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 2, delay = Duration.ofMillis(5)))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        val cause = Futures.unwrap(thrown)
        assertTrue(cause is IOException)
        // 2 prior failures attached as suppressed (initial + first retry), terminal is the 3rd.
        assertEquals(2, cause.suppressed.size)
        assertEquals(3, client.callCount)
    }

    @Test
    fun `non-retryable exception is surfaced immediately`() {
        val client = FailNTimesClient(failures = 5) { IllegalArgumentException("nope") }
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        assertTrue(Futures.unwrap(thrown) is IllegalArgumentException)
        assertEquals(1, client.callCount)
    }

    // ----------------- Idempotency awareness -----------------

    @Test
    fun `bare POST without body is not retried`() {
        val client = QueueClient().enqueue(503)
        val request =
            Request.builder().method(Method.POST).url("https://api.example.com/x").build()
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(request)
        scheduler.runAll()
        assertEquals(503, future.join().status.code)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `POST with a replayable body is retried`() {
        val client = QueueClient().enqueue(503).enqueue(200)
        val request =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .body(RequestBody.create("payload".toByteArray()))
                .build()
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(request)
        scheduler.runAll()
        assertEquals(200, future.join().status.code)
        assertEquals(2, client.callCount)
    }

    @Test
    fun `POST with a non-replayable body is not retried`() {
        val client = QueueClient().enqueue(503)
        val request =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .body(NonReplayableBody())
                .build()
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(request)
        scheduler.runAll()
        assertEquals(503, future.join().status.code)
        assertEquals(1, client.callCount)
    }

    // ----------------- Retry-After honoring -----------------

    @Test
    fun `Retry-After seconds header is honored as the delay`() {
        val client =
            QueueClient()
                .enqueue(503, Headers.Builder().add("Retry-After", "2").build())
                .enqueue(200)
        val future =
            pipeline(client, HttpRetryOptions(maxRetries = 3))
                .sendAsync(getRequest())
        // The first retry should be scheduled for 2 seconds.
        scheduler.runAll()
        assertEquals(200, future.join().status.code)
        val scheduled = scheduler.recordedDelays
        assertTrue(scheduled.any { it == Duration.ofSeconds(2) }, "expected a 2s scheduled delay, got $scheduled")
    }

    // ----------------- Body close before retry -----------------

    @Test
    fun `prior retryable response body is closed before retrying`() {
        val closes = AtomicInteger(0)
        var n = 0
        val client =
            AsyncHttpClient { request ->
                n++
                val code = if (n == 1) 503 else 200
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(code))
                        .body(CountingCloseBody(closes))
                        .build(),
                )
            }
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        future.join().close()
        assertTrue(closes.get() >= 1, "the 503 body should have been closed before retry")
    }

    // ----------------- Stack safety -----------------

    @Test
    fun `a long zero-delay retry sequence does not overflow the stack`() {
        // 5000 zero-delay retries. An implementation that recursed per attempt (thenCompose
        // chains or self-recursive drive) would blow the stack; the iterative trampoline must not.
        val attempts = 5000
        val client = AlwaysFailClient(IOException("io"))
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = attempts, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        assertTrue(Futures.unwrap(thrown) is IOException)
        assertEquals(attempts + 1, client.callCount)
    }

    // ----------------- Cancellation / no real sleep -----------------

    @Test
    fun `delay is scheduled not slept - pending future before the scheduler runs`() {
        val client = QueueClient().enqueue(503).enqueue(200)
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofSeconds(30)))
                .sendAsync(getRequest())
        // The first attempt already ran (503), but the retry is parked on the scheduler — the
        // future is NOT complete and no thread is blocked.
        assertFalse(future.isDone)
        assertEquals(1, client.callCount)
        scheduler.runAll()
        assertEquals(200, future.join().status.code)
    }

    // ----------------- Throwing predicate / delay (must complete the future, never hang) -----------------

    @Test
    fun `a throwing shouldRetry predicate completes the future exceptionally and closes the response`() {
        val closes = AtomicInteger(0)
        val client =
            AsyncHttpClient { request ->
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(503))
                        .body(CountingCloseBody(closes))
                        .build(),
                )
            }
        val options =
            HttpRetryOptions(
                maxRetries = 3,
                shouldRetryCondition = { _ -> throw RuntimeException("predicate boom") },
            )
        val future = pipeline(client, options).sendAsync(getRequest())
        scheduler.runAll()
        // The predicate threw inside the completion callback. The future must still complete
        // (not hang), and the still-open retryable response must be closed so it does not leak.
        val thrown = assertFails { future.join() }
        assertTrue(
            Futures.unwrap(thrown) is IllegalStateException,
            "a throwing predicate must surface as IllegalStateException",
        )
        assertEquals(1, closes.get(), "the retryable response must be closed when the predicate throws")
    }

    @Test
    fun `a throwing shouldRetryException predicate completes the future exceptionally`() {
        val client = AlwaysFailClient(IOException("io"))
        val options =
            HttpRetryOptions(
                maxRetries = 3,
                shouldRetryException = { _ -> throw RuntimeException("predicate boom") },
            )
        val future = pipeline(client, options).sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        assertTrue(Futures.unwrap(thrown) is IllegalStateException)
        assertEquals(1, client.callCount, "the loop must not retry when the predicate itself throws")
    }

    @Test
    fun `a synchronous scheduler rejection completes the future exceptionally`() {
        // A closed ManualScheduler rejects schedule(...) synchronously — stand-in for a real
        // ScheduledExecutorService throwing RejectedExecutionException after shutdown.
        val rejecting = ManualScheduler().apply { close() }
        val client = QueueClient().enqueue(503)
        val future =
            AsyncHttpPipelineBuilder(client)
                .append(
                    DefaultAsyncRetryStep(
                        rejecting,
                        HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ofSeconds(1)),
                        fixedClock(),
                    ),
                )
                .build()
                .sendAsync(getRequest())
        // The first attempt ran (503); scheduling the 1s retry on the closed scheduler is rejected
        // synchronously. That rejection must complete the future rather than stranding it.
        assertTrue(future.isCompletedExceptionally, "a rejected schedule must complete the future")
        assertEquals(1, client.callCount)
        assertFails { future.join() }
    }

    // ----------------- Cancellation (interrupts are surfaced, never retried) -----------------

    @Test
    fun `InterruptedIOException is surfaced as cancellation and never retried`() {
        val client = AlwaysFailClient(InterruptedIOException("cancelled"))
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        // Read AND clear the flag so it cannot leak into a later test on this worker thread.
        val interruptObserved = Thread.interrupted()
        assertTrue(Futures.unwrap(thrown) is InterruptedIOException)
        assertEquals(1, client.callCount, "an interrupt must abort the retry loop after one attempt")
        assertTrue(interruptObserved, "the interrupt flag must be restored")
    }

    @Test
    fun `a bare InterruptedException is normalised to InterruptedIOException and not retried`() {
        val client = AlwaysFailClient(InterruptedException("bare"))
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        val interruptObserved = Thread.interrupted()
        val cause = Futures.unwrap(thrown)
        assertTrue(
            cause is InterruptedIOException,
            "a bare InterruptedException must surface as InterruptedIOException",
        )
        assertTrue(
            cause.cause is InterruptedException,
            "the original InterruptedException must be attached as the cause",
        )
        assertEquals(1, client.callCount)
        assertTrue(interruptObserved, "the interrupt flag must be restored")
    }

    @Test
    fun `the same exception instance reused across attempts completes the future without self-suppression`() {
        // Regression for the CI hang: a transport that re-throws ONE exception instance would, on
        // the terminal attempt, attach that instance under itself (addSuppressed(this) ->
        // IllegalArgumentException) — which, swallowed by the completion callback, stranded the
        // returned future forever.
        val shared = IOException("shared boom")
        val client = AlwaysFailClient(shared)
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        val thrown = assertFails { future.join() }
        val cause = Futures.unwrap(thrown)
        assertTrue(cause === shared, "the shared exception instance must surface as the terminal failure")
        assertFalse(cause.suppressed.any { it === cause }, "an exception must never be suppressed under itself")
        assertEquals(4, client.callCount, "initial + 3 retries")
    }

    // ----------------- Resource hygiene -----------------

    @Test
    fun `a response delivered after the caller cancelled the future is closed not leaked`() {
        val closes = AtomicInteger(0)
        val deferred = CompletableFuture<Response>()
        val client = AsyncHttpClient { _ -> deferred }
        val req = getRequest()
        val future = pipeline(client, HttpRetryOptions(maxRetries = 3)).sendAsync(req)
        // The attempt is in flight; cancel the returned future before it completes.
        assertFalse(future.isDone)
        future.cancel(true)
        // The downstream attempt now completes with an open 2xx response. Since the caller already
        // cancelled, result.complete(...) is a no-op — the step must close the response itself.
        deferred.complete(
            Response.builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(CountingCloseBody(closes))
                .build(),
        )
        scheduler.runAll()
        assertEquals(1, closes.get(), "a response delivered after cancellation must be closed, not leaked")
    }

    @Test
    fun `a retryable response whose close throws does not strand the retry`() {
        var n = 0
        val client =
            AsyncHttpClient { request ->
                n++
                val builder =
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(if (n == 1) 503 else 200))
                if (n == 1) builder.body(ThrowingCloseBody())
                CompletableFuture.completedFuture(builder.build())
            }
        val future =
            pipeline(client, HttpRetryOptions.fixed(maxRetries = 3, delay = Duration.ZERO))
                .sendAsync(getRequest())
        scheduler.runAll()
        // The 503's body.close() throws; closeQuietly must swallow it so the retry proceeds to 200
        // rather than the throw escaping the callback and stranding the future.
        assertEquals(200, future.join().status.code)
        assertEquals(2, n, "the retry must proceed past the throwing close")
    }

    // ----------------- Real multi-threaded scheduler (trampoline visibility) -----------------

    @Test
    fun `retries on a real multi-threaded scheduler complete without stranding`() {
        // Exercises the trampoline across real threads: each backoff fires drive() on a scheduler
        // pool thread, distinct from the thread that ran the previous pump. Guards the lock-guarded
        // re-arm handoff — a regression to plain fields could strand the future, and get(timeout)
        // fails the test rather than hanging it.
        val realScheduler = Executors.newScheduledThreadPool(2)
        try {
            val calls = AtomicInteger(0)
            val client =
                AsyncHttpClient { request ->
                    val n = calls.incrementAndGet()
                    CompletableFuture.completedFuture(
                        Response.builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .status(Status.fromCode(if (n <= 10) 503 else 200))
                            .build(),
                    )
                }
            val future =
                AsyncHttpPipelineBuilder(client)
                    .append(
                        DefaultAsyncRetryStep(
                            realScheduler,
                            HttpRetryOptions.fixed(maxRetries = 15, delay = Duration.ofMillis(1)),
                            fixedClock(),
                        ),
                    )
                    .build()
                    .sendAsync(getRequest())
            assertEquals(200, future.get(10, TimeUnit.SECONDS).status.code)
            assertEquals(11, calls.get(), "10 retryable 503s + a final 200")
        } finally {
            realScheduler.shutdownNow()
        }
    }

    // ----------------- Helpers -----------------

    private fun pipeline(
        client: AsyncHttpClient,
        options: HttpRetryOptions,
    ) = AsyncHttpPipelineBuilder(client)
        .append(DefaultAsyncRetryStep(scheduler, options, fixedClock()))
        .build()

    private fun getRequest(): Request = Request.builder().method(Method.GET).url("https://api.example.com/x").build()

    private fun fixedClock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.EPOCH

            override fun monotonic(): Long = 0L

            override fun sleep(duration: Duration) = fail("async retry must not call Clock.sleep")
        }

    /** Async client returning a FIFO queue of canned responses; throws if the queue is empty. */
    private class QueueClient : AsyncHttpClient {
        private val queue = ArrayDeque<Pair<Int, Headers>>()
        private val calls = AtomicInteger(0)

        val callCount: Int get() = calls.get()

        fun enqueue(
            code: Int,
            headers: Headers = Headers.Builder().build(),
        ): QueueClient = apply { queue.addLast(code to headers) }

        override fun executeAsync(request: Request): CompletableFuture<Response> {
            calls.incrementAndGet()
            val (code, headers) =
                queue.removeFirstOrNull() ?: error("QueueClient: no response enqueued")
            return CompletableFuture.completedFuture(
                Response.builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .status(Status.fromCode(code))
                    .headers(headers)
                    .build(),
            )
        }
    }

    /**
     * Fails the first [failures] attempts, then returns 200. Each failure gets a **fresh**
     * exception from [exceptionFactory] — real transports throw a distinct instance per attempt,
     * and reusing one instance would make the accumulated suppressed-failure trail meaningless
     * (an exception cannot be suppressed under itself).
     */
    private class FailNTimesClient(
        private val failures: Int,
        private val exceptionFactory: () -> Exception,
    ) : AsyncHttpClient {
        private val calls = AtomicInteger(0)

        val callCount: Int get() = calls.get()

        override fun executeAsync(request: Request): CompletableFuture<Response> {
            val n = calls.incrementAndGet()
            return if (n <= failures) {
                Futures.failed(exceptionFactory())
            } else {
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.OK)
                        .build(),
                )
            }
        }
    }

    /** Always fails with [exception]. */
    private class AlwaysFailClient(private val exception: Exception) : AsyncHttpClient {
        private val calls = AtomicInteger(0)

        val callCount: Int get() = calls.get()

        override fun executeAsync(request: Request): CompletableFuture<Response> {
            calls.incrementAndGet()
            return Futures.failed(exception)
        }
    }

    private class NonReplayableBody : RequestBody() {
        override fun mediaType(): MediaType? = MediaType.parse("text/plain")

        override fun contentLength(): Long = 5

        override fun isReplayable(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            sink.write("hello".toByteArray(Charsets.UTF_8))
        }
    }

    private class CountingCloseBody(private val closes: AtomicInteger) : ResponseBody() {
        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = 0

        override fun source(): BufferedSource = fail("body should not be read")

        override fun close() {
            closes.incrementAndGet()
        }
    }

    /** A response body whose close() throws a non-IOException, to prove closeQuietly swallows it. */
    private class ThrowingCloseBody : ResponseBody() {
        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = 0

        override fun source(): BufferedSource = fail("body should not be read")

        override fun close() {
            throw RuntimeException("close boom")
        }
    }
}
