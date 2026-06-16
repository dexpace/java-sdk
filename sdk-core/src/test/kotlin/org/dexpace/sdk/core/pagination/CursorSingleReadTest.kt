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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises the single-read extractor on [CursorPaginationStrategy]: items and the next
 * cursor are pulled from one pass over the response, so a single-use body is read exactly
 * once per page.
 */
class CursorSingleReadTest {
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
     * Parses the body format `items=<csv>\ncursor=<next-or-empty>` into a [CursorResult].
     * Reads the body exactly once and reports each read through [reads].
     */
    private fun singleReadExtractor(reads: AtomicInteger): (Response) -> CursorResult<String> =
        { resp ->
            reads.incrementAndGet()
            val body = resp.body!!.source().use { it.readUtf8() }
            val itemsLine = body.lineSequence().firstOrNull { it.startsWith("items=") } ?: "items="
            val cursorLine = body.lineSequence().firstOrNull { it.startsWith("cursor=") } ?: "cursor="
            val itemsRaw = itemsLine.removePrefix("items=")
            val cursorRaw = cursorLine.removePrefix("cursor=")
            val items = if (itemsRaw.isEmpty()) emptyList() else itemsRaw.split(",")
            CursorResult(items, cursorRaw.ifEmpty { null })
        }

    @Test
    fun `single read extractor reads the body once per page`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=a,b,c\ncursor=abc")
        }
        client.on("https://api.example.com/items?cursor=abc") { req ->
            textResponse(req, "items=d,e,f\ncursor=")
        }

        val reads = AtomicInteger(0)
        val strategy = CursorPaginationStrategy(singleReadExtractor(reads))
        val paginator = Paginator(client, initialRequest(), strategy)

        val collected: List<String> = paginator.iterateAll().toList()

        assertEquals(listOf("a", "b", "c", "d", "e", "f"), collected)
        // Two pages fetched, two HTTP calls, and exactly one body read per page — no double drain.
        assertEquals(2, client.callCount)
        assertEquals(2, reads.get())
    }

    @Test
    fun `single read extractor parses each response exactly once`() {
        // A response whose body cannot be read twice: the second source() call throws. This
        // makes a double-drain a hard failure rather than a silently-cached read.
        val parses = AtomicInteger(0)
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            singleUseResponse(req, "items=x,y\ncursor=")
        }

        val parsing: (Response) -> CursorResult<String> = { resp ->
            parses.incrementAndGet()
            val body = resp.body!!.source().use { it.readUtf8() }
            val itemsRaw = body.substringAfter("items=").substringBefore('\n')
            CursorResult(itemsRaw.split(","), null)
        }
        val strategy = CursorPaginationStrategy(parsing)
        val paginator = Paginator(client, initialRequest(), strategy)

        assertEquals(listOf("x", "y"), paginator.iterateAll().toList())
        assertEquals(1, parses.get())
        assertEquals(1, client.callCount)
    }

    @Test
    fun `CursorResult carries items and next cursor`() {
        val present = CursorResult(listOf("one", "two"), "next-cursor")
        assertEquals(listOf("one", "two"), present.items)
        assertEquals("next-cursor", present.nextCursor)

        val end = CursorResult(listOf("last"), null)
        assertEquals(listOf("last"), end.items)
        assertNull(end.nextCursor)
    }

    @Test
    fun `single read extractor honours a custom cursor query param`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "items=one\ncursor=tok")
        }
        client.on("https://api.example.com/items?page_token=tok") { req ->
            textResponse(req, "items=two\ncursor=")
        }

        val reads = AtomicInteger(0)
        val strategy =
            CursorPaginationStrategy(singleReadExtractor(reads), cursorQueryParam = "page_token")
        val paginator = Paginator(client, initialRequest(), strategy)

        assertEquals(listOf("one", "two"), paginator.iterateAll().toList())
        assertEquals(
            listOf(
                "https://api.example.com/items",
                "https://api.example.com/items?page_token=tok",
            ),
            client.receivedUrls,
        )
    }
}
