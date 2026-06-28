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
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PagedIterableTest {
    @BeforeTest fun setup() = Io.installProvider(OkioIoProvider)

    private fun request(): Request =
        Request.builder().url(URL("https://api.example.com/items")).method(Method.GET).build()

    private fun closeRecordingResponse(closeCount: AtomicInteger): Response {
        val body = object : ResponseBody() {
            override fun mediaType() = null
            override fun contentLength() = -1L
            override fun source() = throw UnsupportedOperationException("not needed")
            override fun close() { closeCount.incrementAndGet() }
        }
        return Response.builder().request(request()).protocol(Protocol.HTTP_1_1)
            .status(Status.OK).body(body).build()
    }

    @Test
    fun `byPage yields fully-usable pages and closing each response is the fetcher's job`() {
        val closeCount = AtomicInteger(0)
        val pages = ArrayDeque(
            listOf(
                "p1" to "n1",
                "p2" to null,
            ),
        )
        val iterable = PagedIterable<String>(
            firstPage = {
                closeRecordingResponse(closeCount).use { resp ->
                    val (item, next) = pages.removeFirst()
                    Page.from(resp, listOf(item), nextLink = next)
                }
            },
            nextPage = { _, _ ->
                closeRecordingResponse(closeCount).use { resp ->
                    val (item, next) = pages.removeFirst()
                    Page.from(resp, listOf(item), nextLink = next)
                }
            },
        )

        val collected = iterable.byPage().toList() // safe: pages are pure values
        assertEquals(listOf("p1", "p2"), collected.map { it.items.single() })
        assertEquals(2, closeCount.get()) // both responses closed by the fetchers' use{}
    }

    @Test
    fun `iterator flattens items across pages`() {
        val pages = ArrayDeque(listOf("a" to "n", "b" to null))
        val iterable = PagedIterable<String>(
            firstPage = { val (i, n) = pages.removeFirst(); Page(listOf(i), 200, Headers.builder().build(), request(), nextLink = n) },
            nextPage = { _, _ -> val (i, n) = pages.removeFirst(); Page(listOf(i), 200, Headers.builder().build(), request(), nextLink = n) },
        )
        assertEquals(listOf("a", "b"), iterable.toList())
    }
}
