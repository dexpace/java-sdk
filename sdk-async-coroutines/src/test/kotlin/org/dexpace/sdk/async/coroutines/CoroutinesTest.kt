package org.dexpace.sdk.async.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CoroutinesTest {

    @Test
    fun `suspend execute awaits the underlying future`() = runBlocking {
        val client = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(mockResponse(request, 200))
        }
        val response = client.execute(getRequest())
        assertEquals(200, response.status.code)
    }

    @Test
    fun `suspend send awaits a pipeline future`() = runBlocking {
        val pipeline = AsyncHttpPipelineBuilder(
            AsyncHttpClient { request -> CompletableFuture.completedFuture(mockResponse(request, 204)) },
        ).build()
        val response = pipeline.send(getRequest())
        assertEquals(204, response.status.code)
    }

    @Test
    fun `suspend execute surfaces the unwrapped exception`() = runBlocking {
        val sentinel = IOException("net")
        val client = AsyncHttpClient { _ ->
            CompletableFuture<Response>().apply { completeExceptionally(sentinel) }
        }
        val thrown = assertFails { client.execute(getRequest()) }
        // kotlinx-coroutines-jdk8's `await()` strips the CompletionException wrapper for us.
        assertEquals("net", thrown.message)
    }

    @Test
    fun `cancelling the coroutine cancels the underlying future`() = runBlocking {
        // `runBlocking` (not `runTest`) — we need real-time, real-dispatcher behavior to verify
        // that cancelling the coroutine causes the CompletableFuture to see `cancel(true)`.
        // `runTest` virtualises the dispatcher which doesn't play well with real
        // `CompletableFuture` callbacks observed across thread boundaries.
        val started = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Unit>()
        val client = AsyncHttpClient { _ ->
            val future = CompletableFuture<Response>()
            future.whenComplete { _, _ -> if (future.isCancelled) cancelled.complete(Unit) }
            started.complete(Unit)
            future
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            try {
                client.execute(getRequest())
            } catch (_: Throwable) {
                // Expected when cancelled.
            }
        }
        // Suspend on the future from the started signal — no busy wait.
        started.await()
        job.cancel()
        // `cancelled` is completed by the future's listener after kotlinx-coroutines-jdk8's
        // `await()` calls `cancel(true)` on the underlying CompletableFuture.
        cancelled.get(2, TimeUnit.SECONDS)
        scope.cancel()
    }

    @Test
    fun `completableFutureOf bridges a suspending block into a future`() = runBlocking {
        coroutineScope {
            val future = completableFutureOf {
                delay(5)
                42
            }
            assertEquals(42, future.get())
        }
    }

    @Test
    fun `asAsyncCoroutines wraps a sync client with coroutine cancellation semantics`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val syncClient = HttpClient { request -> mockResponse(request, 201) }
            val asyncClient = syncClient.asAsyncCoroutines(scope)
            val response = withTimeout(2000) { asyncClient.execute(getRequest()) }
            assertEquals(201, response.status.code)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `completable future from suspending block honors cancellation of the future itself`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val future = completableFutureOf {
                gate.await() // suspend forever until cancelled
                "ran"
            }
            future.cancel(true)
            assertTrue(future.isCancelled)
        }
    }

    private fun getRequest(): Request = Request.builder()
        .method(Method.GET)
        .url(URL("https://api.example.com/"))
        .build()

    private fun mockResponse(request: Request, code: Int): Response = Response.builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .status(Status.fromCode(code))
        .build()
}
