/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.async.netty

import io.netty.util.concurrent.DefaultEventExecutor
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.spi.MDCAdapter
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val client =
            AsyncHttpClient { request ->
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
        val client =
            AsyncHttpClient { _ ->
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
        val pipeline =
            AsyncHttpPipelineBuilder(
                AsyncHttpClient { request -> CompletableFuture.completedFuture(mockResponse(request, 201)) },
            ).build()
        val future = pipeline.sendNetty(getRequest(), executor)
        assertTrue(future.await(2, TimeUnit.SECONDS))
        assertEquals(201, future.now.status.code)
    }

    @Test
    fun `cancelling the Netty promise cancels the underlying CompletableFuture`() {
        val cancelLatch = CompletableFuture<Unit>()
        val sourceFuture =
            CompletableFuture<Response>().also { f ->
                f.whenComplete { _, _ -> if (f.isCancelled) cancelLatch.complete(Unit) }
            }
        val client = AsyncHttpClient { _ -> sourceFuture }
        val nettyFuture = client.executeNetty(getRequest(), executor)
        // Cancel via Netty's API.
        nettyFuture.cancel(true)
        // Wait deterministically for the cancel to propagate to the source future.
        cancelLatch.get(2, TimeUnit.SECONDS)
        assertTrue(sourceFuture.isCancelled, "cancelling the Netty promise should cancel the source CompletableFuture")
    }

    @Test
    fun `completing source future after promise is already cancelled does not throw`() {
        // Race: promise cancelled first, then source future completes — trySuccess must not throw.
        val sourceFuture = CompletableFuture<Response>()
        val client = AsyncHttpClient { _ -> sourceFuture }
        val nettyFuture = client.executeNetty(getRequest(), executor)

        // Cancel the Netty promise.
        nettyFuture.cancel(true)
        assertTrue(nettyFuture.isCancelled)

        // Now complete the source future after the promise is done — this must NOT propagate an exception.
        val completionErrorRef = AtomicReference<Throwable?>()
        val completionLatch = CompletableFuture<Unit>()
        Thread {
            try {
                sourceFuture.complete(mockResponse(getRequest(), 200))
            } catch (t: Throwable) {
                completionErrorRef.set(t)
            } finally {
                completionLatch.complete(Unit)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        completionLatch.get(2, TimeUnit.SECONDS)
        val err = completionErrorRef.get()
        if (err != null) throw AssertionError("trySuccess threw unexpectedly after cancel: $err", err)
        // Promise remains cancelled.
        assertTrue(nettyFuture.isCancelled)
    }

    @Test
    fun `mdc propagates from caller to sync transport via executeNetty`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "netty-transport-test")
        try {
            val seenTraceId = AtomicReference<String?>()
            val async =
                AsyncHttpClient { request ->
                    seenTraceId.set(MDC.get("trace.id"))
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            val nettyFuture = async.executeNetty(getRequest(), executor)
            nettyFuture.get(2, TimeUnit.SECONDS)
            assertEquals("netty-transport-test", seenTraceId.get())
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `mdc propagates into whenComplete callback thread`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "netty-whencomplete-mdc")
        try {
            val seenTraceId = AtomicReference<String?>()
            val gate = CompletableFuture<Unit>()
            val async =
                AsyncHttpClient { request ->
                    val f = CompletableFuture<Response>()
                    // Complete on a separate thread to ensure whenComplete fires off-caller-thread.
                    Thread {
                        gate.get(2, TimeUnit.SECONDS)
                        f.complete(mockResponse(request, 200))
                    }.also {
                        it.isDaemon = true
                        it.start()
                    }
                    f
                }
            val nettyFuture = async.executeNetty(getRequest(), executor)
            // Attach a listener that captures what MDC looks like on the whenComplete thread.
            val mdcLatch = CompletableFuture<Unit>()
            nettyFuture.addListener {
                seenTraceId.set(MDC.get("trace.id"))
                mdcLatch.complete(Unit)
            }
            gate.complete(Unit)
            mdcLatch.get(2, TimeUnit.SECONDS)
            assertEquals("netty-whencomplete-mdc", seenTraceId.get())
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
        }
    }

    @Test
    fun `mdc is preserved on caller thread after executeNetty completes`() {
        val originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
        MDC.put("trace.id", "netty-caller-preserve")
        try {
            val async =
                AsyncHttpClient { request ->
                    CompletableFuture.completedFuture(mockResponse(request, 200))
                }
            async.executeNetty(getRequest(), executor)
                .get(2, TimeUnit.SECONDS)
            assertEquals("netty-caller-preserve", MDC.get("trace.id"))
        } finally {
            MDC.clear()
            restoreMdcAdapter(originalAdapter)
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
