package org.dexpace.sdk.async.netty

import io.netty.util.concurrent.DefaultEventExecutor
import org.dexpace.sdk.core.client.AsyncHttpClient
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyTest {

    private val executor = DefaultEventExecutor()

    @AfterTest
    fun shutdown() {
        executor.shutdownGracefully(0, 0, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS)
    }

    @Test
    fun `executeNetty bridges a completed future into a Netty Promise`() {
        val client = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(mockResponse(request, 200))
        }
        val future = client.executeNetty(getRequest(), executor)
        assertTrue(future.await(2, TimeUnit.SECONDS))
        assertTrue(future.isSuccess)
        assertEquals(200, future.now.status.code)
    }

    @Test
    fun `executeNetty unwraps CompletionException into the original failure`() {
        val sentinel = IOException("net")
        val client = AsyncHttpClient { _ ->
            CompletableFuture<Response>().apply { completeExceptionally(sentinel) }
        }
        val future = client.executeNetty(getRequest(), executor)
        assertTrue(future.await(2, TimeUnit.SECONDS))
        assertEquals(false, future.isSuccess)
        // Netty's `cause()` returns the failure passed to `setFailure(...)` — should be the
        // original IOException, not a CompletionException wrapper.
        assertEquals(sentinel, future.cause())
    }

    @Test
    fun `sendNetty mirrors the pipeline's future onto a Netty promise`() {
        val pipeline = AsyncHttpPipelineBuilder(
            AsyncHttpClient { request -> CompletableFuture.completedFuture(mockResponse(request, 201)) },
        ).build()
        val future = pipeline.sendNetty(getRequest(), executor)
        assertTrue(future.await(2, TimeUnit.SECONDS))
        assertEquals(201, future.now.status.code)
    }

    @Test
    fun `cancelling the Netty promise cancels the underlying CompletableFuture`() {
        val cancelled = AtomicBoolean(false)
        val sourceFuture = CompletableFuture<Response>().also { f ->
            f.whenComplete { _, _ -> if (f.isCancelled) cancelled.set(true) }
        }
        val client = AsyncHttpClient { _ -> sourceFuture }
        val nettyFuture = client.executeNetty(getRequest(), executor)
        // Cancel via Netty's API.
        nettyFuture.cancel(true)
        // Listener fires on the event executor; give it a moment to propagate to the source.
        Thread.sleep(50)
        assertTrue(cancelled.get(), "cancelling the Netty promise should cancel the source CompletableFuture")
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
