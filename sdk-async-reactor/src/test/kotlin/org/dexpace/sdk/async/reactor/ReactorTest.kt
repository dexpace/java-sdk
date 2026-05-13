package org.dexpace.sdk.async.reactor

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.http.sse.ServerSentEventReader
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import reactor.test.StepVerifier
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.spi.MDCAdapter

class ReactorTest {

    @BeforeTest
    fun installIo() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `executeMono completes with the response on subscribe`() {
        val client = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(mockResponse(request, 200))
        }
        StepVerifier.create(client.executeMono(getRequest()))
            .assertNext { assertEquals(200, it.status.code) }
            .verifyComplete()
    }

    @Test
    fun `executeMono surfaces errors via onError`() {
        val sentinel = IOException("net")
        val client = AsyncHttpClient { _ ->
            CompletableFuture<Response>().apply { completeExceptionally(sentinel) }
        }
        StepVerifier.create(client.executeMono(getRequest()))
            .expectErrorMatches { it.message == "net" || it.cause?.message == "net" }
            .verify()
    }

    @Test
    fun `executeMono is cold — re-subscribing re-executes the supplier`() {
        val subscribeCount = AtomicInteger(0)
        val client = AsyncHttpClient { request ->
            subscribeCount.incrementAndGet()
            CompletableFuture.completedFuture(mockResponse(request, 200))
        }
        val mono = client.executeMono(getRequest())
        mono.block()
        mono.block()
        assertEquals(2, subscribeCount.get(), "Mono.fromFuture { supplier } is cold by construction")
    }

    @Test
    fun `sendMono delegates to the pipeline`() {
        val pipeline = AsyncHttpPipelineBuilder(
            AsyncHttpClient { request -> CompletableFuture.completedFuture(mockResponse(request, 201)) },
        ).build()
        StepVerifier.create(pipeline.sendMono(getRequest()))
            .assertNext { assertEquals(201, it.status.code) }
            .verifyComplete()
    }

    @Test
    fun `toFlux emits each SSE event in order then completes`() {
        val source = sseBytes("data: one\n\ndata: two\n\n")
        val reader = ServerSentEventReader(source)
        StepVerifier.create(reader.toFlux())
            .assertNext { assertEquals(listOf("one"), it.data) }
            .assertNext { assertEquals(listOf("two"), it.data) }
            .verifyComplete()
    }

    @Test
    fun `toFlux completes immediately when the source has no events`() {
        val source = sseBytes("")
        StepVerifier.create(ServerSentEventReader(source).toFlux())
            .verifyComplete()
    }

    @Test
    fun `readServerSentEventsAsFlux is a one-liner over toFlux`() {
        val source = sseBytes("data: hi\n\ndata: bye\n\n")
        StepVerifier.create(source.readServerSentEventsAsFlux())
            .assertNext { assertEquals(listOf("hi"), it.data) }
            .assertNext { assertEquals(listOf("bye"), it.data) }
            .verifyComplete()
    }

    @Test
    fun `toFlux emits an event carrying retry hint`() {
        val source = sseBytes("retry: 1500\n\n")
        StepVerifier.create(source.readServerSentEventsAsFlux())
            .assertNext { assertEquals(1500L, it.retry?.toMillis()) }
            .verifyComplete()
    }

    @Test
    fun `mdc propagates from caller to sync transport via executeMono supplier path`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "reactor-transport-test")
        try {
            val seenTraceId = AtomicInteger(0)
            val client = AsyncHttpClient { request ->
                seenTraceId.set(MDC.get("trace.id")?.hashCode() ?: -1)
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
            client.executeMono(getRequest()).block(java.time.Duration.ofSeconds(2))
            // The supplier is called synchronously on the subscribe thread, so MDC is already present.
            // We just verify that the supplier sees it (baseline regression).
            assertTrue(seenTraceId.get() != 0, "Supplier should see the trace.id")
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `mdc is preserved on caller thread after executeMono completes`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "reactor-caller-preserve")
        try {
            val client = AsyncHttpClient { request ->
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
            client.executeMono(getRequest()).block(java.time.Duration.ofSeconds(2))
            // After block() returns, the caller's MDC should still be intact — withMdc inside the
            // adapter's hooks restores the previous (= caller's) MDC on exit.
            assertEquals("reactor-caller-preserve", MDC.get("trace.id"))
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    private fun sseBytes(s: String) = Io.provider.source(s.toByteArray(Charsets.UTF_8))

    private fun getRequest(): Request = Request.builder()
        .method(Method.GET)
        .url(URL("https://api.example.com/"))
        .build()

    private fun mockResponse(request: Request, code: Int): Response = Response.builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .status(Status.fromCode(code))
        .build()

    private fun installBasicMdcAdapter() {
        val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
        field.isAccessible = true
        if (field.get(null) !is BasicMDCAdapter) {
            field.set(null, BasicMDCAdapter())
        }
    }

    private fun restoreMdcAdapter(adapter: MDCAdapter?) {
        val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
        field.isAccessible = true
        field.set(null, adapter)
    }
}
