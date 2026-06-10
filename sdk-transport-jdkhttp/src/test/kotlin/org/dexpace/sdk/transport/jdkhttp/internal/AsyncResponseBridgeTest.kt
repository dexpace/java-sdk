/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.BufferedSource
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.dexpace.sdk.core.http.request.Request as SdkRequest
import org.dexpace.sdk.core.http.response.Response as SdkResponse

/**
 * Tests for [bridgeAsyncResponse], the seam that gives `executeAsync` its two cancellation
 * guarantees: cancelling the returned future aborts the in-flight JDK exchange, and a response
 * delivered in the cancellation race window is closed rather than leaked.
 */
class AsyncResponseBridgeTest {
    @Test
    fun `normal completion yields the adapted response without closing it`() {
        val inFlight = CompletableFuture<HttpResponse<InputStream>>()
        val closed = AtomicBoolean(false)
        val adapted = trackingResponse(closed)

        val result = bridgeAsyncResponse(inFlight) { adapted }
        inFlight.complete(fakeJdkResponse())

        assertSame(adapted, result.get(), "consumer must receive the adapted response")
        assertFalse(closed.get(), "the bridge must not close a response the consumer will own")
    }

    @Test
    fun `cancelling the result cancels the in-flight exchange`() {
        val inFlight = CompletableFuture<HttpResponse<InputStream>>()
        val result = bridgeAsyncResponse(inFlight) { trackingResponse(AtomicBoolean(false)) }

        result.cancel(true)

        assertTrue(inFlight.isCancelled, "cancelling the returned future must abort the JDK exchange")
    }

    @Test
    fun `a response delivered after cancellation is closed to avoid a leak`() {
        // A future whose cancel() is a no-op simulates the race where the JDK has already produced
        // the body when the consumer cancels: the exchange can no longer be aborted, so the bridge
        // must close the adapted response instead of orphaning its connection.
        val inFlight =
            object : CompletableFuture<HttpResponse<InputStream>>() {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            }
        val closed = AtomicBoolean(false)
        val adapted = trackingResponse(closed)

        val result = bridgeAsyncResponse(inFlight) { adapted }
        result.cancel(true)
        // The body arrives after the consumer has already given up.
        inFlight.complete(fakeJdkResponse())

        assertTrue(result.isCancelled)
        assertTrue(closed.get(), "a response delivered after cancellation must be closed, not leaked")
    }

    @Test
    fun `an exceptional exchange completes the result exceptionally`() {
        val inFlight = CompletableFuture<HttpResponse<InputStream>>()
        val result = bridgeAsyncResponse(inFlight) { error("adapt must not run on a failed exchange") }
        val boom = java.io.IOException("boom")

        inFlight.completeExceptionally(boom)

        val ex = assertFailsWith<ExecutionException> { result.get() }
        assertSame(boom, ex.cause)
    }

    @Test
    fun `an adapt failure on a delivered response completes the result exceptionally`() {
        val inFlight = CompletableFuture<HttpResponse<InputStream>>()
        val boom = java.io.IOException("adapt failed")
        val result = bridgeAsyncResponse(inFlight) { throw boom }

        inFlight.complete(fakeJdkResponse())

        val ex = assertFailsWith<ExecutionException> { result.get() }
        assertSame(boom, ex.cause, "an adaptation failure must surface to the caller")
    }

    // ----------------- Helpers -----------------

    private fun trackingResponse(closed: AtomicBoolean): SdkResponse =
        SdkResponse.builder()
            .request(
                SdkRequest.builder().url("https://api.example.test/x").method(Method.GET).build(),
            )
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(
                object : ResponseBody() {
                    override fun mediaType(): MediaType? = null

                    override fun contentLength(): Long = 0

                    override fun source(): BufferedSource = error("body must not be read in this test")

                    override fun close() {
                        closed.set(true)
                    }
                },
            )
            .build()

    /** Minimal [HttpResponse]; the test [adapt] ignores it, so only the type needs to be satisfied. */
    private fun fakeJdkResponse(): HttpResponse<InputStream> =
        object : HttpResponse<InputStream> {
            override fun statusCode(): Int = SC_OK

            override fun request(): HttpRequest = throw UnsupportedOperationException()

            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap<String, List<String>>()) { _, _ -> true }

            override fun body(): InputStream = InputStream.nullInputStream()

            override fun sslSession(): Optional<SSLSession> = Optional.empty()

            override fun uri(): URI = URI.create("https://api.example.test/x")

            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }

    private companion object {
        private const val SC_OK = 200
    }
}
