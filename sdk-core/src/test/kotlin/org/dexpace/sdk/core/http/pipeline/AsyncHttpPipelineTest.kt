/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.pagination.AsyncPaginator
import org.dexpace.sdk.core.pagination.PageInfo
import org.dexpace.sdk.core.pagination.PaginationStrategy
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class AsyncHttpPipelineTest {
    private val executor = Executors.newFixedThreadPool(2)

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun `empty pipeline short-circuits to the transport`() {
        val pipeline = AsyncHttpPipelineBuilder(constantClient(200)).build()
        val response = pipeline.sendAsync(getRequest()).join()
        assertEquals(200, response.status.code)
    }

    @Test
    fun `sendAsync threads RequestOptions to the transport and steps read them`() {
        val client = OptionsRecordingAsyncClient()
        val seenByStep = AtomicReference<RequestOptions?>(null)
        val step =
            TestStep(Stage.PRE_SEND) { _, next ->
                seenByStep.set(next.options)
                next.processAsync()
            }
        val pipeline = AsyncHttpPipelineBuilder(client).append(step).build()
        val options = RequestOptions.builder().maxRetries(0).tag("k", "v").build()

        pipeline.sendAsync(getRequest(), options).join()

        assertSame(options, seenByStep.get(), "the step must read options via next.options")
        assertSame(options, client.seenOptions.single(), "options must reach the terminal dispatch")
    }

    @Test
    fun `empty async pipeline threads options straight to the transport`() {
        val client = OptionsRecordingAsyncClient()
        val pipeline = AsyncHttpPipelineBuilder(client).build()
        val options = RequestOptions.builder().maxRetries(2).build()

        pipeline.sendAsync(getRequest(), options).join()

        assertSame(options, client.seenOptions.single())
    }

    @Test
    fun `sync transport throw inside an empty pipeline becomes a failed future`() {
        // A sync `Exception` thrown by a misbehaving AsyncHttpClient must surface as a
        // failed future, not propagate synchronously to `sendAsync` callers.
        val pipeline =
            AsyncHttpPipelineBuilder(
                AsyncHttpClient { _ -> throw IllegalStateException("boom") },
            ).build()

        val future = pipeline.sendAsync(getRequest())
        assertTrue(future.isCompletedExceptionally)
        val thrown = assertFails { future.join() }
        assertEquals("boom", thrown.cause?.message ?: thrown.message)
    }

    @Test
    fun `single step sees the request and forwards to next async`() {
        val seen = AtomicReference<String>()
        val step =
            TestStep(Stage.PRE_AUTH) { req, next ->
                seen.set(req.url.host)
                next.processAsync()
            }
        val pipeline = AsyncHttpPipelineBuilder(constantClient(200)).append(step).build()
        pipeline.sendAsync(getRequest()).join()
        assertEquals("api.example.com", seen.get())
    }

    @Test
    fun `multiple steps run in stage order`() {
        val callOrder = mutableListOf<String>()
        val pre =
            TestStep(Stage.PRE_AUTH) { _, next ->
                callOrder.add("pre")
                next.processAsync()
            }
        val post =
            TestStep(Stage.POST_AUTH) { _, next ->
                callOrder.add("post")
                next.processAsync()
            }
        // Append in reverse order to confirm stage ordering — not insertion order — wins.
        AsyncHttpPipelineBuilder(constantClient(200))
            .append(post)
            .append(pre)
            .build()
            .sendAsync(getRequest())
            .join()
        assertEquals(listOf("pre", "post"), callOrder)
    }

    @Test
    fun `processAsync(request) substitutes the downstream request`() {
        val seenByClient = AtomicReference<String>()
        val client =
            AsyncHttpClient { request ->
                seenByClient.set(request.headers.get("X-Augmented") ?: "")
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
        val step =
            TestStep(Stage.PRE_AUTH) { req, next ->
                val augmented = req.newBuilder().addHeader("X-Augmented", "yes").build()
                next.processAsync(augmented)
            }
        AsyncHttpPipelineBuilder(client).append(step).build().sendAsync(getRequest()).join()
        assertEquals("yes", seenByClient.get())
    }

    @Test
    fun `next copy lets a step drive the downstream chain twice independently`() {
        // Mirrors the retry/redirect pattern — call next.copy().processAsync() repeatedly.
        val attempts = AtomicInteger(0)
        val client =
            AsyncHttpClient { request ->
                val code = if (attempts.incrementAndGet() < 2) 503 else 200
                CompletableFuture.completedFuture(mockResponse(request, code))
            }
        val retry =
            TestStep(Stage.RETRY) { _, next ->
                // First call.
                next.copy().processAsync().thenCompose { first ->
                    if (first.status.code == 503) {
                        next.copy().processAsync()
                    } else {
                        CompletableFuture.completedFuture(
                            first,
                        )
                    }
                }
            }
        val response =
            AsyncHttpPipelineBuilder(client).append(retry).build()
                .sendAsync(getRequest())
                .join()
        assertEquals(200, response.status.code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `synchronous Exception thrown by a step becomes a failed future`() {
        // Per the AsyncHttpStep contract, sync throws are caller-bug only — but the runtime
        // still normalises them so a misbehaving step can't poison the chain.
        val step = TestStep(Stage.PRE_AUTH) { _, _ -> throw IllegalArgumentException("bad input") }
        val pipeline = AsyncHttpPipelineBuilder(constantClient(200)).append(step).build()
        val future = pipeline.sendAsync(getRequest())
        assertTrue(future.isCompletedExceptionally)
        assertFails { future.join() }
    }

    @Test
    fun `pipeline-level HttpPipeline_toAsync round-trip preserves responses`() {
        val sync = HttpPipelineBuilder(HttpClient { mockResponse(it, 204) }).build()
        val async = sync.toAsync(executor)
        val response = async.sendAsync(getRequest()).join()
        assertEquals(204, response.status.code)
    }

    @Test
    fun `toAsync future cancel(true) interrupts the in-flight sync send`() {
        // A blocking transport awaits an (interruptible) latch. Cancelling the async future with
        // mayInterruptIfRunning=true must interrupt the worker thread so the blocking send aborts
        // — matching native transports' executeAsync cancellation semantics.
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val released = CountDownLatch(1)
        val neverFires = CountDownLatch(1)

        val blockingClient =
            HttpClient { request ->
                started.countDown()
                try {
                    // Blocks until interrupted (the latch is never counted down).
                    neverFires.await()
                    mockResponse(request, 200)
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    Thread.currentThread().interrupt()
                    released.countDown()
                    throw IOException("interrupted", e)
                }
            }

        val async = HttpPipelineBuilder(blockingClient).build().toAsync(executor)
        val future = async.sendAsync(getRequest())

        assertTrue(started.await(2, TimeUnit.SECONDS), "send should have started")
        future.cancel(true)

        assertTrue(released.await(2, TimeUnit.SECONDS), "worker must observe the interrupt")
        assertTrue(interrupted.get(), "the in-flight sync send was interrupted")
        assertTrue(future.isCancelled, "the returned future reports cancelled")
    }

    @Test
    fun `toAsync future cancel(false) does not interrupt the worker`() {
        // cancel(false) cancels the future without interrupting the worker; a blocking send that
        // ignores interrupts therefore runs to completion in the background.
        val started = CountDownLatch(1)
        val cancelObserved = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sawInterrupt = AtomicBoolean(false)

        val client =
            HttpClient { request ->
                started.countDown()
                // Block until the test has issued cancel(false), then sample the interrupt flag.
                cancelObserved.await(2, TimeUnit.SECONDS)
                proceed.await(2, TimeUnit.SECONDS)
                sawInterrupt.set(Thread.currentThread().isInterrupted)
                finished.countDown()
                mockResponse(request, 200)
            }

        val async = HttpPipelineBuilder(client).build().toAsync(executor)
        val future = async.sendAsync(getRequest())

        assertTrue(started.await(2, TimeUnit.SECONDS), "send should have started")
        future.cancel(false)
        cancelObserved.countDown()
        // Let the worker run to completion on its own terms.
        proceed.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS), "worker should run to completion")
        assertTrue(future.isCancelled, "the future reports cancelled")
        assertFalse(sawInterrupt.get(), "cancel(false) must not interrupt the worker")
    }

    @Test
    fun `AsyncHttpPipeline_toBlocking unwraps the wrapper to the original cause`() {
        val async =
            AsyncHttpPipelineBuilder(
                AsyncHttpClient {
                        _ ->
                    CompletableFuture<Response>().apply { completeExceptionally(IOException("oops")) }
                },
            ).build()
        val sync = async.toBlocking()
        val thrown = assertFails { sync.send(getRequest()) }
        assertEquals("oops", thrown.message)
    }

    @Test
    fun `toBlocking honours interruption, throwing InterruptedIOException with the flag set`() {
        // A future that never completes, so toBlocking parks in get() until interrupted.
        val never = CompletableFuture<Response>()
        val async = AsyncHttpPipelineBuilder(AsyncHttpClient { never }).build()
        val sync = async.toBlocking()

        val started = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val flagSet = AtomicBoolean(false)
        val worker =
            Thread {
                started.countDown()
                try {
                    sync.send(getRequest())
                } catch (t: Throwable) {
                    thrown.set(t)
                    flagSet.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        worker.interrupt()
        worker.join(2_000)

        val failure = thrown.get()
        assertTrue(failure is InterruptedIOException, "expected InterruptedIOException, got $failure")
        assertTrue(flagSet.get(), "interrupt flag must be restored on the calling thread")
        assertTrue(never.isCancelled, "the in-flight future must be cancelled on interruption")
    }

    @Test
    fun `builder appendAll appends every step in iteration order`() {
        val a = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val b = TestStep(Stage.POST_AUTH) { _, next -> next.processAsync() }
        val pipeline = AsyncHttpPipelineBuilder(constantClient(200)).appendAll(listOf(a, b)).build()
        assertEquals(listOf(a, b), pipeline.steps)
    }

    @Test
    fun `builder insertAfter splices a step after the first matching type`() {
        val pre = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(pre)
                .insertAfter<TestStep>(TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() })
                .build()
        assertEquals(2, pipeline.steps.size)
    }

    @Test
    fun `builder insertAfter rejects a cross-stage step`() {
        // The anchor is in PRE_AUTH; the inserted step declares POST_AUTH. An unchecked insert
        // would silently relocate it to the POST_AUTH slot rather than "after the anchor", so
        // the builder must reject the cross-stage edit.
        val anchor = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val crossStage = TestStep(Stage.POST_AUTH) { _, next -> next.processAsync() }
        val base = AsyncHttpPipelineBuilder(constantClient(200)).append(anchor).build()

        assertFails {
            AsyncHttpPipelineBuilder.from(base).insertAfter<TestStep>(crossStage)
        }
    }

    @Test
    fun `builder replace rejects a cross-stage replacement`() {
        val original = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val crossStage = TestStep(Stage.POST_AUTH) { _, next -> next.processAsync() }
        val base = AsyncHttpPipelineBuilder(constantClient(200)).append(original).build()

        assertFails {
            AsyncHttpPipelineBuilder.from(base).replace<TestStep>(crossStage)
        }
    }

    @Test
    fun `builder remove drops every matching instance`() {
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() })
                .append(TestStep(Stage.POST_AUTH) { _, next -> next.processAsync() })
                .remove<TestStep>()
                .build()
        assertEquals(0, pipeline.steps.size)
    }

    @Test
    fun `from materialises an AsyncHttpPipeline as a fresh builder`() {
        val first = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val pipeline = AsyncHttpPipelineBuilder(constantClient(200)).append(first).build()
        val rebuilt = AsyncHttpPipelineBuilder.from(pipeline).build()
        assertEquals(1, rebuilt.steps.size)
        assertSame(first, rebuilt.steps[0])
    }

    @Test
    fun `appending a distinct second pillar step throws IllegalStateException`() {
        val builder =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(TestPillarStep(Stage.AUTH))

        val ex = assertFailsWith<IllegalStateException> { builder.append(TestPillarStep(Stage.AUTH)) }
        assertTrue(ex.message!!.contains("AUTH"), "message names the pillar stage: ${ex.message}")
        assertTrue(ex.message!!.contains("replace"), "message points at replace<T>(): ${ex.message}")
    }

    @Test
    fun `same pillar instance re-appended is idempotent`() {
        val auth = TestPillarStep(Stage.AUTH)
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(auth)
                .append(auth)
                .build()
        assertSame(auth, pipeline.steps.single())
    }

    @Test
    fun `replace swaps a pillar step in place`() {
        val replacement = TestPillarStep(Stage.AUTH)
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(TestPillarStep(Stage.AUTH))
                .replace<TestPillarStep>(replacement)
                .build()
        assertSame(replacement, pipeline.steps.single())
    }

    @Test
    fun `executeAsync conforms to the AsyncHttpClient SPI and routes through the steps`() {
        val recorded = AtomicReference<Request>()
        val transportClient =
            AsyncHttpClient { request ->
                recorded.set(request)
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
        val headerStep =
            TestStep(Stage.PRE_SEND) { req, next ->
                next.processAsync(req.newBuilder().setHeader("X-Pipeline", "on").build())
            }
        // Typed as the transport SPI to prove AsyncHttpPipeline conforms to AsyncHttpClient.
        val transport: AsyncHttpClient = AsyncHttpPipelineBuilder(transportClient).append(headerStep).build()

        val response = transport.executeAsync(getRequest()).join()

        assertEquals(200, response.status.code)
        assertEquals("on", recorded.get().headers.get("X-Pipeline"))
    }

    @Test
    fun `AsyncPaginator over the pipeline as AsyncHttpClient walks two pages through the steps`() {
        val fetches = AtomicInteger(0)
        val transportClient =
            AsyncHttpClient { request ->
                fetches.incrementAndGet()
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
        val stepRuns = AtomicInteger(0)
        val countingStep =
            TestStep(Stage.PRE_SEND) { _, next ->
                stepRuns.incrementAndGet()
                next.processAsync()
            }
        val transport: AsyncHttpClient = AsyncHttpPipelineBuilder(transportClient).append(countingStep).build()

        val pageCount = AtomicInteger(0)
        val strategy =
            PaginationStrategy<String> { _, initial ->
                val n = pageCount.incrementAndGet()
                PageInfo(listOf("item-$n"), if (n < 2) initial else null)
            }

        val items = AsyncPaginator(transport, getRequest(), strategy).collectAllAsync().join()

        assertEquals(listOf("item-1", "item-2"), items)
        assertEquals(2, fetches.get(), "two page fetches routed through the pipeline")
        assertEquals(2, stepRuns.get(), "the pipeline step ran on each page")
    }

    @Test
    fun `installing a pillar at Stage_SEND is rejected`() {
        val sendStep = TestPillarStep(Stage.SEND)
        assertFails {
            AsyncHttpPipelineBuilder(constantClient(200)).append(sendStep).build()
        }
    }

    @Test
    fun `prepend places a non-pillar step at the head of its stage`() {
        val tag = AtomicReference<String>()
        val a =
            TestStep(Stage.PRE_AUTH) { _, next ->
                tag.set((tag.get() ?: "") + "a")
                next.processAsync()
            }
        val b =
            TestStep(Stage.PRE_AUTH) { _, next ->
                tag.set((tag.get() ?: "") + "b")
                next.processAsync()
            }
        // `append(a)` then `prepend(b)` should yield order [b, a].
        AsyncHttpPipelineBuilder(constantClient(200))
            .append(a)
            .prepend(b)
            .build()
            .sendAsync(getRequest())
            .join()
        assertEquals("ba", tag.get())
    }

    @Test
    fun `prependAll preserves iteration semantics of single prepend calls`() {
        val a = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val b = TestStep(Stage.PRE_AUTH) { _, next -> next.processAsync() }
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .prependAll(listOf(a, b))
                .build()
        // prepend(a) then prepend(b) → order [b, a].
        assertEquals(listOf(b, a), pipeline.steps)
    }

    // -- Helpers -----------------------------------------------------------------------------

    private class TestStep(
        override val stage: Stage,
        private val body: (Request, AsyncPipelineNext) -> CompletableFuture<Response>,
    ) : AsyncHttpStep {
        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> = body(request, next)
    }

    /** Pillar test step — `stage` is mutable for the SEND-rejection test only. */
    private class TestPillarStep(override val stage: Stage) : AsyncHttpStep {
        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> = fail("pillar should never run in these tests")
    }

    private fun constantClient(code: Int): AsyncHttpClient =
        AsyncHttpClient { request -> CompletableFuture.completedFuture(mockResponse(request, code)) }

    /** Async transport recording the [RequestOptions] threaded into the per-call overload. */
    private inner class OptionsRecordingAsyncClient : AsyncHttpClient {
        val seenOptions: MutableList<RequestOptions> = ArrayList()

        override fun executeAsync(request: Request): CompletableFuture<Response> =
            executeAsync(request, RequestOptions.EMPTY)

        override fun executeAsync(
            request: Request,
            options: RequestOptions,
        ): CompletableFuture<Response> {
            seenOptions.add(options)
            return CompletableFuture.completedFuture(mockResponse(request, 200))
        }
    }

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url(URL("https://api.example.com/"))
            .build()

    private fun mockResponse(
        request: Request,
        code: Int,
    ): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(code))
            .build()
}
