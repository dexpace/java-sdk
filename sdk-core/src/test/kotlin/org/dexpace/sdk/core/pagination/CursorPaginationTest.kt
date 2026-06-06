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
import java.util.IdentityHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorPaginationTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

    /**
     * Parses the body format `items=<csv>\ncursor=<next-or-empty>` into a (items, cursor)
     * pair. Reads the body exactly once.
     */
    private fun parsePayload(resp: Response): Pair<List<String>, String?> {
        val body = resp.body!!.source().use { it.readUtf8() }
        val itemsLine = body.lineSequence().firstOrNull { it.startsWith("items=") } ?: "items="
        val cursorLine = body.lineSequence().firstOrNull { it.startsWith("cursor=") } ?: "cursor="
        val itemsRaw = itemsLine.removePrefix("items=")
        val cursorRaw = cursorLine.removePrefix("cursor=")
        val items = if (itemsRaw.isEmpty()) emptyList() else itemsRaw.split(",")
        val cursor: String? = cursorRaw.ifEmpty { null }
        return Pair(items, cursor)
    }

    /**
     * Pair of extractors that share a per-Response identity-keyed cache so the
     * single-use body is read exactly once per page even though the strategy's contract
     * splits items + cursor into two calls.
     */
    private fun buildCachedExtractors(): Pair<(Response) -> List<String>, (Response) -> String?> {
        val cache: MutableMap<Response, Pair<List<String>, String?>> = IdentityHashMap()
        val items: (Response) -> List<String> = { r ->
            cache.getOrPut(r) { parsePayload(r) }.first
        }
        val cursor: (Response) -> String? = { r ->
            cache.getOrPut(r) { parsePayload(r) }.second
        }
        return Pair(items, cursor)
    }

    @Test
    fun `cursor pagination walks three pages then stops`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=a,b,c\ncursor=abc")
        }
        client.on("https://api.example.com/items?cursor=abc") { req ->
            textResponse(req, "items=d,e,f\ncursor=def")
        }
        client.on("https://api.example.com/items?cursor=def") { req ->
            textResponse(req, "items=g,h,i\ncursor=")
        }

        val (items, cursor) = buildCachedExtractors()
        val strategy = CursorPaginationStrategy(items, cursor, cursorQueryParam = "cursor")
        val paginator = Paginator(client, initialRequest(), strategy)

        val collected: List<String> = paginator.iterateAll().toList()

        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), collected)
        assertEquals(3, client.callCount)
        assertEquals(
            listOf(
                "https://api.example.com/items",
                "https://api.example.com/items?cursor=abc",
                "https://api.example.com/items?cursor=def",
            ),
            client.receivedUrls,
        )
    }

    @Test
    fun `cursor pagination returns first-page items only when cursor is absent on page 1`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=only-1,only-2\ncursor=")
        }
        val (items, cursor) = buildCachedExtractors()
        val strategy = CursorPaginationStrategy(items, cursor, "cursor")
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("only-1", "only-2"), paginator.iterateAll().toList())
        assertEquals(1, client.callCount)
    }

    @Test
    fun `cursor pagination preserves headers on follow-up requests`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            // First request: confirm authorization header was sent.
            assertEquals("Bearer xyz", req.headers.get("Authorization"))
            textResponse(req, "items=a\ncursor=next")
        }
        client.on("https://api.example.com/items?cursor=next") { req ->
            // Second request: confirm the same authorization header is forwarded.
            assertEquals("Bearer xyz", req.headers.get("Authorization"))
            textResponse(req, "items=b\ncursor=")
        }
        val authRequest =
            Request.builder()
                .url("https://api.example.com/items")
                .method(Method.GET)
                .addHeader("Authorization", "Bearer xyz")
                .build()

        val (items, cursor) = buildCachedExtractors()
        val strategy = CursorPaginationStrategy(items, cursor)
        val paginator = Paginator(client, authRequest, strategy)
        assertEquals(listOf("a", "b"), paginator.iterateAll().toList())
    }
}
