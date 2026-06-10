/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaginatorCapTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/loop")
            .method(Method.GET)
            .build()

    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) emptyList() else body.split(",")
    }

    /**
     * A server that always points `rel="next"` back at the same URL never advances its
     * cursor. Without a safety cap this drives an unbounded fetch loop; [Paginator.maxPages]
     * must terminate it after exactly [maxPages] fetches.
     */
    private fun stuckCursorClient(): StubHttpClient {
        val client = StubHttpClient()
        client.on("https://api.example.com/loop") { req ->
            // The next link points right back at this same URL — the cursor never advances.
            textResponse(
                req,
                "item",
                extraHeaders = mapOf("Link" to "<https://api.example.com/loop>; rel=\"next\""),
            )
        }
        return client
    }

    @Test
    fun `maxPages caps a server that returns the same cursor forever`() {
        val client = stuckCursorClient()
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy, maxPages = 3L)

        // Each page yields one item; the cap stops fetching after 3 pages.
        assertEquals(listOf("item", "item", "item"), paginator.iterateAll().toList())
        assertEquals(3, client.callCount)
    }

    @Test
    fun `maxPages of one fetches only the first page`() {
        val client = stuckCursorClient()
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy, maxPages = 1L)

        assertEquals(listOf("item"), paginator.iterateAll().toList())
        assertEquals(1, client.callCount)
    }

    @Test
    fun `default maxPages does not interfere with a normally-terminating stream`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/loop") { req ->
            textResponse(
                req,
                "a,b",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/loop?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/loop?page=2") { req ->
            // No Link header → natural end of stream well before any cap.
            textResponse(req, "c")
        }
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        // No maxPages argument → defaults to Long.MAX_VALUE.
        val paginator = Paginator(client, initialRequest(), strategy)

        assertEquals(listOf("a", "b", "c"), paginator.iterateAll().toList())
        assertEquals(2, client.callCount)
    }

    @Test
    fun `non-positive maxPages is rejected`() {
        val client = StubHttpClient()
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        assertFailsWith<IllegalArgumentException> {
            Paginator(client, initialRequest(), strategy, maxPages = 0L)
        }
    }
}
