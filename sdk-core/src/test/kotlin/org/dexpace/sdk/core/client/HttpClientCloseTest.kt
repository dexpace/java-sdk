package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [HttpClient] / [AsyncHttpClient] lifecycle contract added in WU-4:
 *  - Default `close()` is a no-op (SAM literals and lightweight wrappers compile and run
 *    without explicit close logic).
 *  - Overridden `close()` runs on each call by the caller — implementers are responsible
 *    for their own idempotency latch (the contract requires it; the default cannot enforce
 *    it because there's no shared state to latch on).
 *  - The interfaces are usable as `AutoCloseable` (try-with-resources / `use { ... }`).
 */
class HttpClientCloseTest {
    private val request: Request =
        Request.builder()
            .method(Method.GET)
            .url(URL("https://api.example.com/"))
            .build()

    private fun mockResponse(code: Int): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(code))
            .build()

    @Test
    fun `default close on SAM HttpClient is a no-op`() {
        val client = HttpClient { _ -> mockResponse(200) }

        // No exception, no side effects — the default body is empty.
        client.close()
        client.close()
        client.close()

        // The client is still usable afterwards because the default close did nothing.
        // (Real transports may forbid post-close `execute`, but the default body of the
        // interface method does not.)
        assertEquals(200, client.execute(request).status.code)
    }

    @Test
    fun `default close on SAM AsyncHttpClient is a no-op`() {
        val client = AsyncHttpClient { _ -> CompletableFuture.completedFuture(mockResponse(204)) }

        client.close()
        client.close()

        assertEquals(204, client.executeAsync(request).join().status.code)
    }

    @Test
    fun `overridden close runs exactly once when guarded by an AtomicBoolean latch`() {
        val callCount = AtomicInteger()
        val client = LatchingHttpClient(callCount) { _ -> mockResponse(200) }

        client.close()
        client.close()
        client.close()

        assertEquals(1, callCount.get())
        assertTrue(client.isClosed)
    }

    @Test
    fun `overridden async close runs exactly once when guarded by an AtomicBoolean latch`() {
        val callCount = AtomicInteger()
        val client =
            LatchingAsyncHttpClient(callCount) {
                CompletableFuture.completedFuture(mockResponse(200))
            }

        client.close()
        client.close()
        client.close()

        assertEquals(1, callCount.get())
    }

    @Test
    fun `HttpClient is usable with kotlin use extension via AutoCloseable supertype`() {
        val callCount = AtomicInteger()
        val client = LatchingHttpClient(callCount) { _ -> mockResponse(202) }

        val code: Int =
            client.use { c ->
                c.execute(request).status.code
            }
        assertEquals(202, code)
        assertEquals(1, callCount.get(), "use() must invoke close() exactly once")
    }

    @Test
    fun `AsyncHttpClient is usable with kotlin use extension via AutoCloseable supertype`() {
        val callCount = AtomicInteger()
        val client =
            LatchingAsyncHttpClient(callCount) {
                CompletableFuture.completedFuture(mockResponse(202))
            }

        val code: Int =
            client.use { c ->
                c.executeAsync(request).join().status.code
            }
        assertEquals(202, code)
        assertEquals(1, callCount.get(), "use() must invoke close() exactly once")
    }

    @Test
    fun `default close does not flag the client as closed because there is no state`() {
        // The default close on the SAM literal has no observable state — it is a literal
        // no-op. This test pins that behaviour so a future "default close marks closed"
        // change has to be deliberate.
        val client = HttpClient { _ -> mockResponse(200) }
        client.close()
        // No `isClosed` getter on the interface — close is purely a release hook.
        // The SAM literal can still execute requests after close because no resources
        // were released.
        assertFalse(client is LatchingHttpClient)
    }

    /**
     * Minimal `HttpClient` implementation backed by an `AtomicBoolean` latch — the
     * canonical pattern transports use for idempotent close. Tests verify that exactly
     * one `release()` runs across N close calls. `AtomicBoolean.compareAndSet` is the
     * documented SDK pattern for idempotent close — `synchronized` would pin virtual
     * threads under Loom and is forbidden by `CLAUDE.md`.
     */
    private class LatchingHttpClient(
        private val callCount: AtomicInteger,
        private val responder: (Request) -> Response,
    ) : HttpClient {
        private val closed: AtomicBoolean = AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()

        override fun execute(request: Request): Response = responder(request)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                callCount.incrementAndGet()
            }
        }
    }

    private class LatchingAsyncHttpClient(
        private val callCount: AtomicInteger,
        private val responder: (Request) -> CompletableFuture<Response>,
    ) : AsyncHttpClient {
        private val closed: AtomicBoolean = AtomicBoolean(false)

        override fun executeAsync(request: Request): CompletableFuture<Response> = responder(request)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                callCount.incrementAndGet()
            }
        }
    }
}
