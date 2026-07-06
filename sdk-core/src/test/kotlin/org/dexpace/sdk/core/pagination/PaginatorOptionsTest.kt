/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the paginators thread their per-call [RequestOptions] into each page fetch. Both clients
 * override only the options-aware overload and make the bare `execute`/`executeAsync` throw, so a
 * paginator that dropped the options (calling the 1-arg overload) fails loudly rather than passing.
 */
class PaginatorOptionsTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

    // An empty page terminates pagination after exactly one fetch.
    private val itemsExtractor =
        ItemsExtractor<String> { resp ->
            val body = resp.body!!.source().use { it.readUtf8() }
            if (body.isEmpty()) emptyList() else body.split(",")
        }

    private fun options(): RequestOptions =
        RequestOptions.builder().maxRetries(0).tag("origin", "test").build()

    @Test
    fun `sync paginator threads its RequestOptions into every page fetch`() {
        val opts = options()
        val seen = mutableListOf<RequestOptions>()
        val client =
            object : HttpClient {
                override fun execute(request: Request): Response =
                    error("Paginator must call the options-aware execute(request, options) overload")

                override fun execute(
                    request: Request,
                    options: RequestOptions,
                ): Response {
                    seen.add(options)
                    return textResponse(request, "") // empty page → terminate
                }
            }
        val paginator =
            Paginator(client, initialRequest(), PageNumberPaginationStrategy(itemsExtractor), options = opts)

        paginator.iterateAll().toList()

        assertEquals(listOf(opts), seen, "the paginator's options must reach the transport on each fetch")
    }

    @Test
    fun `async paginator threads its RequestOptions into every page fetch`() {
        val opts = options()
        val seen = mutableListOf<RequestOptions>()
        val client =
            object : AsyncHttpClient {
                override fun executeAsync(request: Request): CompletableFuture<Response> =
                    error("AsyncPaginator must call the options-aware executeAsync(request, options) overload")

                override fun executeAsync(
                    request: Request,
                    options: RequestOptions,
                ): CompletableFuture<Response> {
                    seen.add(options)
                    return CompletableFuture.completedFuture(textResponse(request, ""))
                }
            }
        val paginator =
            AsyncPaginator(client, initialRequest(), PageNumberPaginationStrategy(itemsExtractor), options = opts)

        paginator.collectAllAsync().get()

        assertEquals(listOf(opts), seen, "the async paginator's options must reach the transport on each fetch")
    }
}
