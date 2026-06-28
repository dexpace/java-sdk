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
    fun `byPage exposes one rich page per HTTP exchange with status and items`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req -> textResponse(req, "items=a,b\ncursor=abc") }
        client.on("https://api.example.com/items?cursor=abc") { req -> textResponse(req, "items=c\ncursor=") }
        val paginator = Paginator(client, initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val pages = paginator.byPage().toList()

        assertEquals(2, pages.size)
        assertEquals(listOf("a", "b"), pages[0].items)
        assertEquals(listOf("c"), pages[1].items)
        assertEquals(200, pages[0].statusCode)
        assertEquals(2, client.callCount)
    }
}
