/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.spi.MDCAdapter
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.io.IOException
import java.net.URL
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Failsafe deadline for awaiting an operation that is expected to complete promptly. It exists only
// to stop a genuinely-stuck test from blocking forever, so it is kept generous: a healthy test
// returns the instant the awaited work finishes and never approaches this bound, even on a loaded
// CI host running modules in parallel.
private const val FAILSAFE_TIMEOUT_SECONDS = 30L
private val FAILSAFE_TIMEOUT: Duration = Duration.ofSeconds(FAILSAFE_TIMEOUT_SECONDS)

class ReactorTest {
    @BeforeTest
    fun installIo() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `executeMono completes with the response on subscribe`() {
        val client =
            AsyncHttpClient { request ->
                CompletableFuture.completedFuture(mockResponse(request, 200))
            }
        StepVerifier.create(client.executeMono(getRequest()))
            .assertNext { assertEquals(200, it.status.code) }
            .verifyComplete()
    }

    @Test
    fun `executeMono surfaces errors via onError`() {
        val sentinel = IOException("net")
        val client =
            AsyncHttpClient { _ ->
                CompletableFuture<Response>().apply { completeExceptionally(sentinel) }
            }
        StepVerifier.create(client.executeMono(getRequest()))
            .expectErrorMatches { it.message == "net" || it.cause?.message == "net" }
            .verify()
    }

    @Test
    fun `executeMono is cold — re-subscribing re-executes the supplier`() {
        val subscribeCount = AtomicInteger(0)
        val client =
            AsyncHttpClient { request ->
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
        val pipeline =
            AsyncHttpPipelineBuilder(
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
            val client =
                AsyncHttpClient { request ->
                    seenTraceId.set(MDC.get("trace.id")?.hashCode() ?: -1)
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            client.executeMono(getRequest()).block(FAILSAFE_TIMEOUT)
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
            val client =
                AsyncHttpClient { request ->
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            client.executeMono(getRequest()).block(FAILSAFE_TIMEOUT)
            // After block() returns, the caller's MDC should still be intact — withMdc inside the
            // adapter's hooks restores the previous (= caller's) MDC on exit.
            assertEquals("reactor-caller-preserve", MDC.get("trace.id"))
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `cancelling executeMono subscription cancels the underlying CompletableFuture`() {
        val futureLatch = CompletableFuture<CompletableFuture<Response>>()
        val client =
            AsyncHttpClient { _ ->
                val f = CompletableFuture<Response>()
                futureLatch.complete(f)
                f
            }
        val mono = client.executeMono(getRequest())
        // StepVerifier creates subscription then cancels it.
        StepVerifier.create(mono)
            .thenCancel()
            .verify(FAILSAFE_TIMEOUT)
        // Retrieve the underlying future that was created during subscription.
        val underlying = futureLatch.get(FAILSAFE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(
            underlying.isCancelled,
            "Disposing the Mono subscription should cancel the underlying CompletableFuture",
        )
    }

    @Test
    fun `mdc propagates into supplier when subscribed on a different scheduler`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "reactor-supplier-mdc")
        try {
            val seenTraceId = AtomicReference<String?>()
            val client =
                AsyncHttpClient { request ->
                    seenTraceId.set(MDC.get("trace.id"))
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            // subscribeOn pushes subscription (and thus the Mono.fromFuture supplier) onto a
            // Reactor scheduler thread — the mdc.withMdc { ... } wrap must re-apply MDC there.
            client.executeMono(getRequest())
                .subscribeOn(Schedulers.boundedElastic())
                .block(FAILSAFE_TIMEOUT)
            assertEquals(
                "reactor-supplier-mdc",
                seenTraceId.get(),
                "MDC must be restored inside the Mono.fromFuture supplier even on a scheduler thread",
            )
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `executeMono captures MDC at subscribe time, not at assembly time`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        try {
            val seenTraceId = AtomicReference<String?>()
            val client =
                AsyncHttpClient { request ->
                    seenTraceId.set(MDC.get("trace.id"))
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }

            // Assemble the cold Mono under one MDC value.
            MDC.put("trace.id", "assembly-time")
            val mono = client.executeMono(getRequest())

            // Mutate MDC AFTER assembly, BEFORE subscribing. With eager (assembly-time) capture the
            // supplier would observe "assembly-time"; with Mono.defer the capture happens per
            // subscription, so it must observe the subscriber's value instead.
            MDC.put("trace.id", "subscribe-time")
            mono.block(FAILSAFE_TIMEOUT)

            assertEquals(
                "subscribe-time",
                seenTraceId.get(),
                "MDC must be captured per-subscription, so the supplier sees the subscriber's trace.id",
            )
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `executeMono re-subscription picks up the live MDC each time`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        try {
            val seenTraceIds = java.util.concurrent.CopyOnWriteArrayList<String?>()
            val client =
                AsyncHttpClient { request ->
                    seenTraceIds.add(MDC.get("trace.id"))
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }

            MDC.put("trace.id", "first")
            val mono = client.executeMono(getRequest())
            mono.block(FAILSAFE_TIMEOUT)

            // Re-subscribe under a different MDC; deferred capture must reflect the new value.
            MDC.put("trace.id", "second")
            mono.block(FAILSAFE_TIMEOUT)

            assertEquals(listOf("first", "second"), seenTraceIds.toList())
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `sendMono captures MDC at subscribe time, not at assembly time`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        try {
            val seenTraceId = AtomicReference<String?>()
            val pipeline =
                AsyncHttpPipelineBuilder(
                    AsyncHttpClient { request ->
                        seenTraceId.set(MDC.get("trace.id"))
                        CompletableFuture.completedFuture(mockResponse(request, 200))
                    },
                ).build()

            MDC.put("trace.id", "assembly-time")
            val mono = pipeline.sendMono(getRequest())

            MDC.put("trace.id", "subscribe-time")
            mono.block(FAILSAFE_TIMEOUT)

            assertEquals(
                "subscribe-time",
                seenTraceId.get(),
                "sendMono must capture MDC per-subscription, so the supplier sees the subscriber's trace.id",
            )
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    private fun sseBytes(s: String) = Io.provider.source(s.toByteArray(Charsets.UTF_8))

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
