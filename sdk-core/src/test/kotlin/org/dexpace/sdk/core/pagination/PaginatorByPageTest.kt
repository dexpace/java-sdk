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

class PaginatorByPageTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

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
    fun `byPage exposes one rich page per HTTP exchange with live response, then closes each`() {
        val closes = AtomicInteger(0)
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req -> closeRecordingResponse(req, closes, "items=a,b\ncursor=abc") }
        client.on("https://api.example.com/items?cursor=abc") { req -> closeRecordingResponse(req, closes, "items=c\ncursor=") }
        val paginator = Paginator(client, initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val collectedItems = mutableListOf<List<String>>()
        val collectedStatuses = mutableListOf<Int>()
        paginator.byPage().use { pages ->
            var idx = 0
            for (p in pages) {
                // The current page's response is live inside the loop body (not closed yet).
                assertEquals(idx, closes.get(), "current page's response must still be open")
                // Raw access: the live Response and per-page status are readable.
                assertEquals(200, p.response.status.code)
                collectedStatuses += p.statusCode
                collectedItems += p.items
                idx++
            }
        }

        assertEquals(listOf(listOf("a", "b"), listOf("c")), collectedItems)
        assertEquals(listOf(200, 200), collectedStatuses)
        assertEquals(2, client.callCount)
        assertEquals(2, closes.get(), "each page's response must be closed after the use-block")
    }

    @Test
    fun `byPage stream yields one page per HTTP exchange and closes each`() {
        val closes = AtomicInteger(0)
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req -> closeRecordingResponse(req, closes, "items=a,b\ncursor=abc") }
        client.on("https://api.example.com/items?cursor=abc") { req -> closeRecordingResponse(req, closes, "items=c\ncursor=") }
        val paginator = Paginator(client, initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val count = paginator.byPage().use { it.stream().count() }

        assertEquals(2L, count)
        assertEquals(2, client.callCount)
        assertEquals(2, closes.get(), "fully consuming byPage().stream() must close every page response")
    }
}
