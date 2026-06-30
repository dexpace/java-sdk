/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PageTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun request(): Request =
        Request.builder().method(
            Method.GET,
        ).url(URL("https://api.example.com/items")).build()

    private fun response(
        closeCount: AtomicInteger,
        statusCode: Int = 200,
        headerName: String = "X-Total",
        headerValue: String = "9",
    ): Response {
        val body =
            object : ResponseBody() {
                override fun mediaType() = null

                override fun contentLength() = -1L

                override fun source() = throw UnsupportedOperationException("not needed")

                override fun close() {
                    closeCount.incrementAndGet()
                }
            }
        return Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(statusCode))
            .headers(Headers.builder().add(headerName, headerValue).build())
            .body(body)
            .build()
    }

    @Test
    fun `holds the live response and derives status, headers, request from it`() {
        val closeCount = AtomicInteger(0)
        val response = response(closeCount, statusCode = 204)
        val page = Page(response, items = listOf(1, 2, 3), nextLink = "https://api.example.com/items?page=2")

        assertSame(response, page.response)
        assertEquals(204, page.statusCode)
        assertEquals(listOf("9"), page.headers.values("X-Total"))
        assertEquals("https://api.example.com/items", page.request.url.toString())
        assertEquals("https://api.example.com/items?page=2", page.nextLink)
        assertEquals(0, closeCount.get()) // constructing a Page must NOT close the response
    }

    @Test
    fun `close closes the underlying response`() {
        val closeCount = AtomicInteger(0)
        val page = Page(response(closeCount), items = listOf("a"))

        assertEquals(0, closeCount.get())
        page.close()
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `items, statusCode, headers and request survive close`() {
        val closeCount = AtomicInteger(0)
        val page = Page(response(closeCount, statusCode = 201), items = listOf("a", "b"))

        page.close()

        // Materialized + derived state is still readable after close; only the raw body is released.
        assertEquals(listOf("a", "b"), page.items)
        assertEquals(201, page.statusCode)
        assertEquals(listOf("9"), page.headers.values("X-Total"))
        assertEquals("https://api.example.com/items", page.request.url.toString())
        assertEquals(1, closeCount.get())
    }

    @Test
    fun `link and token fields default to null`() {
        val page = Page(response(AtomicInteger(0)), items = listOf("a", "b"))
        assertNull(page.continuationToken)
        assertNull(page.nextLink)
        assertNull(page.previousLink)
        assertNull(page.firstLink)
        assertNull(page.lastLink)
    }
}
