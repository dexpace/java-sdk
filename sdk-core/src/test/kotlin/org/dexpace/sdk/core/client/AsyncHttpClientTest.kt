/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `asBlocking returns a sync client that completes normally`() {
        val asyncClient =
            AsyncHttpClient { request ->
                CompletableFuture.completedFuture(mockResponse(request, 204))
            }
        val syncClient = asyncClient.asBlocking()
        val response = syncClient.execute(getRequest())
        assertEquals(204, response.status.code)
    }

    @Test
    fun `asBlocking surfaces the original exception, not the ExecutionException wrapper`() {
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
    fun `asBlocking honours interruption, throwing InterruptedIOException with the flag set`() {
        // A future that never completes, so asBlocking parks in get() until interrupted.
        val never = CompletableFuture<Response>()
        val asyncClient = AsyncHttpClient { never }
        val syncClient = asyncClient.asBlocking()

        val started = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val flagSet = AtomicBoolean(false)
        val worker =
            Thread {
                started.countDown()
                try {
                    syncClient.execute(getRequest())
                } catch (t: Throwable) {
                    thrown.set(t)
                    flagSet.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        // Wait for the worker to reach the blocking call, then interrupt it.
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // Give it a moment to park in get() before interrupting.
        Thread.sleep(50)
        worker.interrupt()
        worker.join(2_000)

        val failure = thrown.get()
        assertTrue(failure is InterruptedIOException, "expected InterruptedIOException, got $failure")
        assertTrue(flagSet.get(), "interrupt flag must be restored on the calling thread")
        assertTrue(never.isCancelled, "the in-flight future must be cancelled on interruption")
    }

    @Test
    fun `asAsync future cancel(true) interrupts the in-flight blocking send`() {
        // The blocking transport parks on a latch that never fires; cancelling the async future
        // with mayInterruptIfRunning=true must interrupt the worker so the send aborts, matching
        // the pipeline-level `toAsync` cancellation semantics.
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val released = CountDownLatch(1)
        val neverFires = CountDownLatch(1)

        val blockingClient =
            HttpClient { request ->
                started.countDown()
                try {
                    neverFires.await()
                    mockResponse(request, 200)
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    Thread.currentThread().interrupt()
                    released.countDown()
                    throw IOException("interrupted", e)
                }
            }

        val future = blockingClient.asAsync(executor).executeAsync(getRequest())

        assertTrue(started.await(2, TimeUnit.SECONDS), "send should have started")
        future.cancel(true)

        assertTrue(released.await(2, TimeUnit.SECONDS), "worker must observe the interrupt")
        assertTrue(interrupted.get(), "the in-flight sync send was interrupted")
        assertTrue(future.isCancelled, "the returned future reports cancelled")
    }

    @Test
    fun `asAsync future cancel(false) does not interrupt the worker`() {
        // cancel(false) cancels the future without interrupting; a blocking send that ignores
        // interrupts therefore runs to completion in the background.
        val started = CountDownLatch(1)
        val cancelObserved = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sawInterrupt = AtomicBoolean(false)

        val client =
            HttpClient { request ->
                started.countDown()
                cancelObserved.await(2, TimeUnit.SECONDS)
                proceed.await(2, TimeUnit.SECONDS)
                sawInterrupt.set(Thread.currentThread().isInterrupted)
                finished.countDown()
                mockResponse(request, 200)
            }

        val future = client.asAsync(executor).executeAsync(getRequest())

        assertTrue(started.await(2, TimeUnit.SECONDS), "send should have started")
        future.cancel(false)
        cancelObserved.countDown()
        proceed.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS), "worker should run to completion")
        assertTrue(future.isCancelled, "the future reports cancelled")
        assertFalse(sawInterrupt.get(), "cancel(false) must not interrupt the worker")
    }

    @Test
    fun `asAsync threads per-call RequestOptions into the wrapped sync client`() {
        // The SPI's 2-arg overload has an options-ignoring default; the bridge must override it so
        // a per-call override survives instead of collapsing to RequestOptions.EMPTY.
        val seen = AtomicReference<RequestOptions?>(null)
        val syncClient =
            object : HttpClient {
                override fun execute(request: Request): Response = execute(request, RequestOptions.EMPTY)

                override fun execute(
                    request: Request,
                    options: RequestOptions,
                ): Response {
                    seen.set(options)
                    return mockResponse(request, 200)
                }
            }
        val options = RequestOptions.builder().maxRetries(3).tag("k", "v").build()

        syncClient.asAsync(executor).executeAsync(getRequest(), options).get(2, TimeUnit.SECONDS)

        assertSame(options, seen.get(), "per-call options must reach the wrapped sync client")
    }

    @Test
    fun `asBlocking threads per-call RequestOptions into the wrapped async client`() {
        val seen = AtomicReference<RequestOptions?>(null)
        val asyncClient =
            object : AsyncHttpClient {
                override fun executeAsync(request: Request): CompletableFuture<Response> =
                    executeAsync(request, RequestOptions.EMPTY)

                override fun executeAsync(
                    request: Request,
                    options: RequestOptions,
                ): CompletableFuture<Response> {
                    seen.set(options)
                    return CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            }
        val options = RequestOptions.builder().maxRetries(0).tag("k", "v").build()

        asyncClient.asBlocking().execute(getRequest(), options)

        assertSame(options, seen.get(), "per-call options must reach the wrapped async client")
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
