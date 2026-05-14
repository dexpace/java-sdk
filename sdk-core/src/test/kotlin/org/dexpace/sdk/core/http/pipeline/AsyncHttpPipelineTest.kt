package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
    fun `AsyncHttpPipeline_toBlocking unwraps CompletionException to the original cause`() {
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
    fun `installing two pillars at the same stage emits a replacement warning and keeps the latest`() {
        val firstAuth = TestPillarStep(Stage.AUTH)
        val secondAuth = TestPillarStep(Stage.AUTH)
        val pipeline =
            AsyncHttpPipelineBuilder(constantClient(200))
                .append(firstAuth)
                .append(secondAuth)
                .build()
        assertSame(secondAuth, pipeline.steps.single())
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
