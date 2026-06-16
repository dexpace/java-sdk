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
import java.util.stream.Collectors
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
     * Single-pass extractor for the body format `items=<csv>\ncursor=<next-or-empty>`. Reads
     * the body exactly once and returns both the items and the next cursor as a
     * [CursorResult], so there is no double-drain of the single-use response body.
     */
    private val extractor: (Response) -> CursorResult<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        val itemsLine = body.lineSequence().firstOrNull { it.startsWith("items=") } ?: "items="
        val cursorLine = body.lineSequence().firstOrNull { it.startsWith("cursor=") } ?: "cursor="
        val itemsRaw = itemsLine.removePrefix("items=")
        val cursorRaw = cursorLine.removePrefix("cursor=")
        val items = if (itemsRaw.isEmpty()) emptyList() else itemsRaw.split(",")
        CursorResult(items, cursorRaw.ifEmpty { null })
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

        val strategy = CursorPaginationStrategy(extractor, cursorQueryParam = "cursor")
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
        val strategy = CursorPaginationStrategy(extractor, "cursor")
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

        val strategy = CursorPaginationStrategy(extractor)
        val paginator = Paginator(client, authRequest, strategy)
        assertEquals(listOf("a", "b"), paginator.iterateAll().toList())
    }

    @Test
    fun `streamAll yields the same items as iterateAll`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=1,2\ncursor=c1")
        }
        client.on("https://api.example.com/items?cursor=c1") { req ->
            textResponse(req, "items=3,4\ncursor=")
        }

        val strategy = CursorPaginationStrategy(extractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        val streamed: List<String> = paginator.streamAll().collect(Collectors.toList())
        assertEquals(listOf("1", "2", "3", "4"), streamed)
    }

    @Test
    fun `cursor with special characters is URL encoded in next request`() {
        // Opaque cursors may contain `=` `+` `/` characters (base64) — the rebuilder must
        // URL-encode them so the server sees the original value unmangled. A custom query
        // param name (e.g. `page_token`) covers token-style APIs that reuse this strategy.
        val rawCursor = "a+b/c="
        val encoded = "a%2Bb%2Fc%3D"
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=one\ncursor=$rawCursor")
        }
        client.on("https://api.example.com/items?page_token=$encoded") { req ->
            textResponse(req, "items=two\ncursor=")
        }

        val strategy = CursorPaginationStrategy(extractor, cursorQueryParam = "page_token")
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("one", "two"), paginator.iterateAll().toList())
        assertEquals(
            listOf(
                "https://api.example.com/items",
                "https://api.example.com/items?page_token=$encoded",
            ),
            client.receivedUrls,
        )
    }
}
