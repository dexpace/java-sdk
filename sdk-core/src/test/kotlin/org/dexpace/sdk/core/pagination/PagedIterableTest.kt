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
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PagedIterableTest {
    @BeforeTest fun setup() = Io.installProvider(OkioIoProvider)

    private fun request(): Request =
        Request.builder().url(URL("https://api.example.com/items")).method(Method.GET).build()

    private fun <T> page(
        items: List<T>,
        nextLink: String? = null,
        continuationToken: String? = null,
    ): Page<T> =
        Page(
            items = items,
            statusCode = 200,
            headers = Headers.builder().build(),
            request = request(),
            continuationToken = continuationToken,
            nextLink = nextLink,
        )

    private fun closeRecordingResponse(closeCount: AtomicInteger): Response {
        val body =
            object : ResponseBody() {
                override fun mediaType() = null

                override fun contentLength() = -1L

                override fun source() = throw UnsupportedOperationException("not needed")

                override fun close() {
                    closeCount.incrementAndGet()
                }
            }
        return Response.builder().request(request()).protocol(Protocol.HTTP_1_1)
            .status(Status.OK).body(body).build()
    }

    // -------------------------------------------------------------------------
    // Existing tests (kept)
    // -------------------------------------------------------------------------

    @Test
    fun `byPage yields fully-usable pages and closing each response is the fetcher's job`() {
        val closeCount = AtomicInteger(0)
        val pages =
            ArrayDeque(
                listOf(
                    "p1" to "n1",
                    "p2" to null,
                ),
            )
        val iterable =
            PagedIterable<String>(
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

        val collected = iterable.byPage().toList()
        assertEquals(listOf("p1", "p2"), collected.map { it.items.single() })
        assertEquals(2, closeCount.get())
    }

    @Test
    fun `iterator flattens items across pages`() {
        val pages = ArrayDeque(listOf("a" to "n", "b" to null))
        val iterable =
            PagedIterable<String>(
                firstPage = {
                    val (i, n) = pages.removeFirst()
                    Page(listOf(i), 200, Headers.builder().build(), request(), nextLink = n)
                },
                nextPage = { _, _ ->
                    val (i, n) = pages.removeFirst()
                    Page(listOf(i), 200, Headers.builder().build(), request(), nextLink = n)
                },
            )
        assertEquals(listOf("a", "b"), iterable.toList())
    }

    // -------------------------------------------------------------------------
    // Behavior 1: nextLink wins over continuationToken when both are present
    // -------------------------------------------------------------------------

    @Test
    fun `nextLink wins over continuationToken when both are present`() {
        val observedLink = arrayOfNulls<String>(1)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1), nextLink = "the-link", continuationToken = "the-token") },
                nextPage = { _, link ->
                    observedLink[0] = link
                    page(listOf(2))
                },
            )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals("the-link", observedLink[0], "nextLink must take precedence over continuationToken")
    }

    // -------------------------------------------------------------------------
    // Behavior 2: empty-string link terminates iteration
    // -------------------------------------------------------------------------

    @Test
    fun `empty string nextLink terminates iteration`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2), nextLink = "") },
                nextPage = { _, _ ->
                    nextPageCalls.incrementAndGet()
                    page(listOf(99))
                },
            )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(0, nextPageCalls.get(), "nextPage must not be called for an empty nextLink")
    }

    @Test
    fun `empty string continuationToken terminates iteration`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2), continuationToken = "") },
                nextPage = { _, _ ->
                    nextPageCalls.incrementAndGet()
                    page(listOf(99))
                },
            )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(0, nextPageCalls.get(), "nextPage must not be called for an empty continuationToken")
    }

    // -------------------------------------------------------------------------
    // Behavior 3: null first-page return yields nothing
    // -------------------------------------------------------------------------

    @Test
    fun `null firstPage return yields empty iteration`() {
        val iterable = PagedIterable<Int>(firstPage = { null })
        assertEquals(emptyList(), iterable.toList())
        assertEquals(emptyList<Page<Int>>(), iterable.byPage().toList())
    }

    // -------------------------------------------------------------------------
    // Behavior 4: empty items page with non-null nextLink continues
    // -------------------------------------------------------------------------

    @Test
    fun `empty items page with non-null nextLink still continues to next page`() {
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(emptyList(), nextLink = "p2") },
                nextPage = { _, _ -> page(listOf(1, 2)) },
            )
        assertEquals(listOf(1, 2), iterable.toList())
    }

    // -------------------------------------------------------------------------
    // Behavior 5: maxPages cap
    // -------------------------------------------------------------------------

    @Test
    fun `maxPages cap stops after exactly N pages`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(10), nextLink = "loop") },
                nextPage = { _, _ ->
                    nextPageCalls.incrementAndGet()
                    page(listOf(20), nextLink = "loop")
                },
                maxPages = 2L,
            )
        assertEquals(listOf(10, 20), iterable.toList())
        assertEquals(1, nextPageCalls.get(), "third page must never be fetched when cap is 2")
    }

    // -------------------------------------------------------------------------
    // Behavior 6: PagingOptions forwarded to both fetchers
    // -------------------------------------------------------------------------

    @Test
    fun `byPage forwards PagingOptions instance to firstPage and nextPage`() {
        val seenFirst = arrayOfNulls<PagingOptions>(1)
        val seenNext = arrayOfNulls<PagingOptions>(1)
        val iterable =
            PagedIterable<Int>(
                firstPage = { opts ->
                    seenFirst[0] = opts
                    page(listOf(1), nextLink = "p2")
                },
                nextPage = { opts, _ ->
                    seenNext[0] = opts
                    page(listOf(2))
                },
            )
        val opts = PagingOptions(offset = 5L, pageSize = 10L)
        iterable.byPage(opts).toList()
        assertSame(opts, seenFirst[0], "firstPage must receive the exact PagingOptions passed to byPage")
        assertSame(opts, seenNext[0], "nextPage must receive the exact PagingOptions passed to byPage")
    }

    // -------------------------------------------------------------------------
    // Behavior 7: IOException propagation
    // -------------------------------------------------------------------------

    @Test
    fun `IOException from firstPage propagates from hasNext`() {
        val iterable =
            PagedIterable<Int>(firstPage = {
                throw IOException("network failure")
            })
        val iterator = iterable.iterator()
        val ex = assertFailsWith<IOException> { iterator.hasNext() }
        assertEquals("network failure", ex.message)
    }

    @Test
    fun `IOException from nextPage propagates after first page is drained`() {
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2), nextLink = "p2") },
                nextPage = { _, _ -> throw IOException("server error on page 2") },
            )
        val iterator = iterable.iterator()
        assertTrue(iterator.hasNext())
        assertEquals(1, iterator.next())
        assertEquals(2, iterator.next())
        val ex = assertFailsWith<IOException> { iterator.hasNext() }
        assertEquals("server error on page 2", ex.message)
    }

    // -------------------------------------------------------------------------
    // Behavior 8: re-iteration restarts pagination
    // -------------------------------------------------------------------------

    @Test
    fun `byPage called twice restarts pagination and re-invokes firstPage`() {
        val firstPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(firstPage = {
                firstPageCalls.incrementAndGet()
                page(listOf(7, 8, 9))
            })
        val run1 = iterable.byPage().map { it.items }.toList()
        val run2 = iterable.byPage().map { it.items }.toList()
        assertEquals(listOf(listOf(7, 8, 9)), run1)
        assertEquals(listOf(listOf(7, 8, 9)), run2)
        assertEquals(2, firstPageCalls.get(), "firstPage must be called once per byPage iteration")
    }

    // -------------------------------------------------------------------------
    // Behavior 9: laziness — consuming page 1 only does not invoke nextPage
    // -------------------------------------------------------------------------

    @Test
    fun `consuming only first item does not invoke nextPage`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2, 3), nextLink = "p2") },
                nextPage = { _, _ ->
                    nextPageCalls.incrementAndGet()
                    page(listOf(4, 5, 6))
                },
            )
        val first = iterable.iterator().next()
        assertEquals(1, first)
        assertEquals(0, nextPageCalls.get(), "nextPage must not be called when only the first item is consumed")
    }

    // -------------------------------------------------------------------------
    // Behavior 10: stream() and pageStream() interop
    // -------------------------------------------------------------------------

    @Test
    fun `stream produces same items as toList`() {
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2), nextLink = "p2") },
                nextPage = { _, _ -> page(listOf(3, 4)) },
            )
        val fromStream: List<Int> = iterable.stream().collect(Collectors.toList())
        assertEquals(listOf(1, 2, 3, 4), fromStream)
        assertEquals(listOf(1, 2, 3, 4), iterable.toList())
    }

    @Test
    fun `pageStream produces pages with correct item content`() {
        val iterable =
            PagedIterable<String>(
                firstPage = { page(listOf("a", "b"), nextLink = "p2") },
                nextPage = { _, _ -> page(listOf("c")) },
            )
        val pages: List<Page<String>> = iterable.pageStream().collect(Collectors.toList())
        assertEquals(2, pages.size)
        assertEquals(listOf("a", "b"), pages[0].items)
        assertEquals(listOf("c"), pages[1].items)
    }

    // -------------------------------------------------------------------------
    // Fix 3: maxPages == 0 throws at construction (behavior changed from yielding
    // nothing to failing fast, aligned with Paginator)
    // -------------------------------------------------------------------------

    @Test
    fun `maxPages zero throws IllegalArgumentException at construction`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                PagedIterable<Int>(firstPage = { null }, maxPages = 0L)
            }
        assertTrue(
            ex.message?.contains("maxPages") == true,
            "message should mention maxPages, was: ${ex.message}",
        )
    }
}
