package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AsyncHttpClientTest {
    private val executor = Executors.newSingleThreadExecutor()

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun `asAsync wraps a sync client and completes the future on the provided executor`() {
        val syncClient = HttpClient { request -> mockResponse(request, 200) }
        val asyncClient = syncClient.asAsync(executor)

        val response = asyncClient.executeAsync(getRequest()).get(2, TimeUnit.SECONDS)
        assertEquals(200, response.status.code)
    }

    @Test
    fun `asAsync completes exceptionally when the sync client throws`() {
        val syncClient = HttpClient { _ -> throw IOException("transport failure") }
        val asyncClient = syncClient.asAsync(executor)

        val future = asyncClient.executeAsync(getRequest())
        val thrown = assertFails { future.join() }
        // join() wraps in CompletionException; the original IOException is the cause.
        val cause = thrown.cause ?: thrown
        assertEquals("transport failure", cause.message)
    }

    @Test
    fun `asBlocking returns a sync client that unwraps the CompletionException`() {
        val asyncClient =
            AsyncHttpClient { request ->
                CompletableFuture.completedFuture(mockResponse(request, 204))
            }
        val syncClient = asyncClient.asBlocking()
        val response = syncClient.execute(getRequest())
        assertEquals(204, response.status.code)
    }

    @Test
    fun `asBlocking surfaces the original exception, not the CompletionException wrapper`() {
        val sentinel = IOException("network")
        val asyncClient =
            AsyncHttpClient {
                    _ ->
                CompletableFuture<Response>().apply { completeExceptionally(sentinel) }
            }
        val syncClient = asyncClient.asBlocking()
        val thrown = assertFails { syncClient.execute(getRequest()) }
        assertEquals(sentinel.message, thrown.message)
    }

    @Test
    fun `round-trip via asAsync then asBlocking preserves the response`() {
        val syncClient = HttpClient { request -> mockResponse(request, 201) }
        val roundTripped = syncClient.asAsync(executor).asBlocking()
        val response = roundTripped.execute(getRequest())
        assertEquals(201, response.status.code)
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
