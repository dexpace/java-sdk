package org.dexpace.sdk.async.virtualthreads

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualThreadsTest {

    @Test
    fun `asAsyncVirtualThreads runs the call on a virtual thread`() {
        val carrierName = AtomicReference<String>()
        val isVirtual = AtomicReference<Boolean>()
        val syncClient = HttpClient { request ->
            val thread = Thread.currentThread()
            carrierName.set(thread.name)
            isVirtual.set(thread.isVirtual)
            mockResponse(request, 200)
        }

        syncClient.asAsyncVirtualThreads().use { vtClient ->
            val response = vtClient.executeAsync(getRequest()).get(2, TimeUnit.SECONDS)
            assertEquals(200, response.status.code)
        }
        assertTrue(isVirtual.get() == true, "expected the blocking call to run on a virtual thread, ran on ${carrierName.get()}")
    }

    @Test
    fun `close shuts the virtual-thread executor down`() {
        val vt = HttpClient { request -> mockResponse(request, 200) }.asAsyncVirtualThreads()
        // Drive one request to ensure the executor is live.
        vt.executeAsync(getRequest()).get(2, TimeUnit.SECONDS)
        vt.close()
        // After close, the executor service has terminated; submitting again would throw.
        val thrown = runCatching { vt.executeAsync(getRequest()).get(2, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(thrown != null, "expected closed virtual-thread executor to reject new tasks")
    }

    @Test
    fun `many concurrent requests fan out cheaply on virtual threads`() {
        val executions = java.util.concurrent.atomic.AtomicInteger(0)
        val syncClient = HttpClient { request ->
            // Simulate a tiny blocking delay so concurrency is observable.
            Thread.sleep(5)
            executions.incrementAndGet()
            mockResponse(request, 200)
        }
        syncClient.asAsyncVirtualThreads().use { vt ->
            val futures = (1..100).map { vt.executeAsync(getRequest()) }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        assertEquals(100, executions.get())
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
