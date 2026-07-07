/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.exception.NotFoundException
import org.dexpace.sdk.core.http.response.exception.ServerErrorException
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.MockResponse
import org.dexpace.sdk.core.util.Futures
import org.dexpace.sdk.io.OkioIoProvider
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsyncThrowOnHttpErrorStepTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `stage is PRE_REDIRECT`() {
        assertEquals(Stage.PRE_REDIRECT, AsyncThrowOnHttpErrorStep().stage)
    }

    @Test
    fun `404 completes exceptionally with the mapped NotFoundException and a readable body`() {
        val client = cannedClient(status = 404, body = "{\"error\":\"missing\"}")

        val pipeline = AsyncHttpPipelineBuilder(client).throwOnHttpError().build()

        val future = pipeline.sendAsync(getRequest())
        val thrown = runCatching { future.join() }.exceptionOrNull()
        val cause = Futures.unwrap(assertNotNull(thrown, "future must complete exceptionally"))
        val notFound = cause as NotFoundException
        assertEquals(404, notFound.status.code)
        assertEquals("{\"error\":\"missing\"}", notFound.body!!.source().readUtf8())
    }

    @Test
    fun `non-canonical 5xx completes exceptionally with a mapped ServerErrorException`() {
        // The step branches on Status.isError directly (a plain 400..599 range check), so a
        // vendor-specific 5xx code with no canonical Status constant is still mapped.
        val client = cannedClient(status = 599, body = "boom")

        val pipeline = AsyncHttpPipelineBuilder(client).throwOnHttpError().build()

        val thrown = runCatching { pipeline.sendAsync(getRequest()).join() }.exceptionOrNull()
        val cause = Futures.unwrap(assertNotNull(thrown, "future must complete exceptionally"))
        val serverError = cause as ServerErrorException
        assertEquals(599, serverError.status.code)
    }

    @Test
    fun `200 completes with the response untouched and the body intact`() {
        val client = cannedClient(status = 200, body = "hello")

        val pipeline = AsyncHttpPipelineBuilder(client).throwOnHttpError().build()

        val response = pipeline.sendAsync(getRequest()).join()
        assertEquals(200, response.status.code)
        assertEquals("hello", response.body!!.source().readUtf8())
        response.close()
    }

    /** An [AsyncHttpClient] that completes with a single canned [status] + [body] per call. */
    private fun cannedClient(
        status: Int,
        body: String,
    ): AsyncHttpClient =
        AsyncHttpClient { request ->
            val response: Response =
                MockResponse.Builder()
                    .status(status)
                    .body(body, MediaType.parse("application/json"))
                    .build()
                    .toResponse(request)
            CompletableFuture.completedFuture(response)
        }

    private fun getRequest(): Request =
        Request.builder().method(Method.GET).url("https://api.example.com/thing").build()
}
