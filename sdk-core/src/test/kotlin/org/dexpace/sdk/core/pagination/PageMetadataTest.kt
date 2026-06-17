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
import kotlin.test.assertNull

/**
 * Covers the page-level metadata ([Page.statusCode], [Page.headers], and the HATEOAS links /
 * continuation token) that the unified pagination surface exposes — the signals the retired
 * `PagedResponse` used to carry, now folded into the single [Page] contract.
 */
class PageMetadataTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/repo/issues")
            .method(Method.GET)
            .build()

    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) emptyList() else body.split(",")
    }

    @Test
    fun `default Page metadata accessors are null`() {
        val page: Page<String> =
            object : Page<String> {
                override val items: List<String> = listOf("a")
                override val hasNext: Boolean = false

                override fun nextPageRequest(): Request? = null
            }
        assertNull(page.statusCode)
        assertNull(page.headers)
        assertNull(page.nextLink)
        assertNull(page.previousLink)
        assertNull(page.firstLink)
        assertNull(page.lastLink)
        assertNull(page.continuationToken)
    }

    @Test
    fun `link header strategy surfaces status, headers, and all four navigation links`() {
        val request = initialRequest()
        val linkHeader =
            "<https://api.example.com/repo/issues?page=2>; rel=\"next\", " +
                "<https://api.example.com/repo/issues?page=1>; rel=\"prev\", " +
                "<https://api.example.com/repo/issues?page=1>; rel=\"first\", " +
                "<https://api.example.com/repo/issues?page=42>; rel=\"last\""
        val response =
            textResponse(
                request,
                "i1,i2",
                extraHeaders = mapOf("Link" to linkHeader, "X-Total-Count" to "84"),
            )

        val page = LinkHeaderPaginationStrategy(itemsExtractor).parse(response, request)

        assertEquals(200, page.statusCode)
        assertEquals("84", page.headers?.get("X-Total-Count"))
        assertEquals("https://api.example.com/repo/issues?page=2", page.nextLink)
        assertEquals("https://api.example.com/repo/issues?page=1", page.previousLink)
        assertEquals("https://api.example.com/repo/issues?page=1", page.firstLink)
        assertEquals("https://api.example.com/repo/issues?page=42", page.lastLink)
        assertNull(page.continuationToken)
    }

    @Test
    fun `link header strategy leaves links null when no Link header is present`() {
        val request = initialRequest()
        val response = textResponse(request, "only")

        val page = LinkHeaderPaginationStrategy(itemsExtractor).parse(response, request)

        assertEquals(200, page.statusCode)
        assertNull(page.nextLink)
        assertNull(page.previousLink)
        assertNull(page.firstLink)
        assertNull(page.lastLink)
    }

    @Test
    fun `cursor strategy carries the next cursor as continuationToken`() {
        val request = initialRequest()
        val response = textResponse(request, "i1,i2")
        val extractor: (Response) -> CursorResult<String> = { resp ->
            val items = resp.body!!.source().use { it.readUtf8() }.split(",")
            CursorResult(items, nextCursor = "cursor-xyz")
        }

        val page = CursorPaginationStrategy(extractor).parse(response, request)

        assertEquals(200, page.statusCode)
        assertEquals("cursor-xyz", page.continuationToken)
        assertNull(page.nextLink)
    }

    @Test
    fun `cursor strategy leaves continuationToken null on the terminal page`() {
        val request = initialRequest()
        val response = textResponse(request, "i1")
        val extractor: (Response) -> CursorResult<String> = { resp ->
            val items = resp.body!!.source().use { it.readUtf8() }.split(",")
            CursorResult(items, nextCursor = null)
        }

        val page = CursorPaginationStrategy(extractor).parse(response, request)

        assertEquals(200, page.statusCode)
        assertNull(page.continuationToken)
    }

    @Test
    fun `page number strategy records status and headers on both populated and empty pages`() {
        val request = initialRequest()
        val populated =
            textResponse(request, "a,b", extraHeaders = mapOf("X-Page" to "1"))
        val empty =
            textResponse(request, "", extraHeaders = mapOf("X-Page" to "9"))
        val strategy = PageNumberPaginationStrategy(itemsExtractor)

        val populatedPage = strategy.parse(populated, request)
        assertEquals(200, populatedPage.statusCode)
        assertEquals("1", populatedPage.headers?.get("X-Page"))

        val emptyPage = strategy.parse(empty, request)
        assertEquals(200, emptyPage.statusCode)
        assertEquals("9", emptyPage.headers?.get("X-Page"))
        assertEquals(false, emptyPage.hasNext)
    }
}
