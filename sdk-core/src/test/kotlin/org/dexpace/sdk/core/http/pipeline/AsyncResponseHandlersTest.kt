/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.ResponseHandler
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.http.response.exception.NetworkException
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.util.Futures
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AsyncResponseHandlersTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `handleWith applies the handler on success and closes the response`() {
        val body = FlagBody("hello world")
        val future = CompletableFuture.completedFuture(responseWith(body))

        val result = future.handleWith(ResponseHandler.string()).join()

        assertEquals("hello world", result)
        assertTrue(body.closed.get(), "the response must be closed after handling")
    }

    @Test
    fun `handleWith closes the response even when the handler does not`() {
        val body = FlagBody("ignored")
        val future = CompletableFuture.completedFuture(responseWith(body))
        // A handler that reads nothing and does not close — handleWith must still close.
        val nonClosing = ResponseHandler<Int> { 42 }

        val result = future.handleWith(nonClosing).join()

        assertEquals(42, result)
        assertTrue(body.closed.get(), "handleWith must guarantee the response is closed")
    }

    @Test
    fun `handleWith surfaces the unwrapped cause on failure, not the CompletionException wrapper`() {
        val boom = NetworkException("connection reset")
        val future = Futures.failed<Response>(boom)

        val ee = assertFailsWith<ExecutionException> { future.handleWith(ResponseHandler.string()).get() }

        // get() unwraps one CompletionException layer; the cause must be the original network
        // failure, not a nested CompletionException wrapper.
        assertSame(boom, ee.cause)
        assertSame(boom, Futures.unwrap(ee))
    }

    @Test
    fun `handleWith propagates a handler IOException as an exceptional completion`() {
        val body = FlagBody("data")
        val future = CompletableFuture.completedFuture(responseWith(body))
        val failing = ResponseHandler<String> { throw IOException("parse failed") }

        val ee = assertFailsWith<ExecutionException> { future.handleWith(failing).get() }

        assertTrue(ee.cause is IOException)
        assertEquals("parse failed", ee.cause?.message)
        assertTrue(body.closed.get(), "the response must still be closed when the handler throws")
    }

    @Test
    fun `sendAsync with handler pipes the response through handleWith`() {
        val body = FlagBody("payload")
        val client = AsyncHttpClient { CompletableFuture.completedFuture(responseWith(body)) }
        val pipeline = AsyncHttpPipelineBuilder(client).build()

        val result = pipeline.sendAsync(request(), ResponseHandler.string()).join()

        assertEquals("payload", result)
        assertTrue(body.closed.get(), "the response must be closed after handling")
    }

    // -- Helpers -----------------------------------------------------------------------------

    private class FlagBody(payload: String) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val source = Io.provider.source(payload.toByteArray())

        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = source

        override fun close() {
            closed.set(true)
            source.close()
        }
    }

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.test/p")
            .method(Method.GET)
            .build()

    private fun responseWith(body: ResponseBody): Response =
        Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(body)
            .build()
}
