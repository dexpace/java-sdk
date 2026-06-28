/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PagedIterableTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun request(): Request =
        Request.builder().url(URL("https://api.example.com/items")).method(Method.GET).build()

    private fun <T> page(
        items: List<T>,
        nextLink: String? = null,
        continuationToken: String? = null,
        closeCount: AtomicInteger = AtomicInteger(0),
    ): Page<T> =
        Page(
            closeRecordingResponse(request(), closeCount),
            items = items,
            continuationToken = continuationToken,
            nextLink = nextLink,
        )

    /** Three single-item pages ("p1","p2","p3") sharing one [closes] counter and a raw [body]. */
    private fun threePageIterable(
        closes: AtomicInteger,
        body: String = "",
    ): PagedIterable<String> {
        val pages = ArrayDeque(listOf("p1" to "n1", "p2" to "n2", "p3" to null))
        fun build(): Page<String> {
            val (item, next) = pages.removeFirst()
            return Page(
                closeRecordingResponse(request(), closes, body = body, extraHeaders = mapOf("X-Total" to "9")),
                listOf(item),
                nextLink = next,
            )
        }
        return PagedIterable(firstPage = { build() }, nextPage = { _, _ -> build() })
    }

    // -------------------------------------------------------------------------
    // Close semantics — the heart of the live-Response model
    // -------------------------------------------------------------------------

    @Test
    fun `byPage use over all pages closes every page response`() {
        val closes = AtomicInteger(0)
        val items = mutableListOf<String>()
        threePageIterable(closes).byPage().use { pages ->
            for (p in pages) {
                items += p.items.single()
            }
        }
        assertEquals(listOf("p1", "p2", "p3"), items)
        assertEquals(3, closes.get(), "use {} + full iteration must close all 3 page responses")
    }

    @Test
    fun `full byPage iteration without use still closes all pages -- last on exhaustion`() {
        val closes = AtomicInteger(0)
        for (p in threePageIterable(closes).byPage()) {
            p.items.single()
        }
        assertEquals(3, closes.get(), "the last page must be closed when the source is exhausted")
    }

    @Test
    fun `early break without use leaves exactly one page open and close releases it`() {
        val closes = AtomicInteger(0)
        val view = threePageIterable(closes).byPage()
        var seen = 0
        for (p in view) {
            seen++
            if (seen == 2) break // break while page 2 is still current/open
        }
        assertEquals(1, closes.get(), "after breaking on page 2 only page 1 (advanced past) is closed")

        view.close()
        assertEquals(2, closes.get(), "close() must release the still-held page 2")

        view.close()
        assertEquals(2, closes.get(), "close() is idempotent — no double-close")
    }

    @Test
    fun `raw access -- current page response and headers are live inside the loop body`() {
        val closes = AtomicInteger(0)
        threePageIterable(closes, body = "raw-bytes").byPage().use { pages ->
            var idx = 0
            for (p in pages) {
                // The current page has not been closed yet: pages closed so far == pages advanced past.
                assertEquals(idx, closes.get(), "the current page's response must still be open")
                // Raw access works: headers are readable and the live body can be drained.
                assertEquals("9", p.headers.values("X-Total").single())
                val body = p.response.body
                assertNotNull(body, "current page must expose a live response body")
                assertEquals("raw-bytes", body.source().use { it.readUtf8() })
                idx++
            }
        }
        assertEquals(3, closes.get())
    }

    @Test
    fun `item iterator partial consume eager-closes the producing page and does not fetch the next`() {
        val closes = AtomicInteger(0)
        val nextCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { Page(closeRecordingResponse(request(), closes), listOf(1, 2, 3), nextLink = "p2") },
                nextPage = { _, _ ->
                    nextCalls.incrementAndGet()
                    Page(closeRecordingResponse(request(), closes), listOf(4, 5, 6))
                },
            )
        val first = iterable.iterator().next()
        assertEquals(1, first)
        assertEquals(1, closes.get(), "items() must eager-close the page before yielding its items")
        assertEquals(0, nextCalls.get(), "a partial consume must not fetch the next page")
    }

    @Test
    fun `full item iteration closes every page`() {
        val closes = AtomicInteger(0)
        val collected = threePageIterable(closes).toList()
        assertEquals(listOf("p1", "p2", "p3"), collected)
        assertEquals(3, closes.get(), "the item path must close all 3 pages")
    }

    @Test
    fun `byPage builds pages from a response the fetcher does not close`() {
        // Fetcher hands the live response to Page(...) and does NOT close it; the view closes it.
        val closes = AtomicInteger(0)
        val pages = ArrayDeque(listOf("p1" to "n1", "p2" to null))
        val iterable =
            PagedIterable<String>(
                firstPage = {
                    val (item, next) = pages.removeFirst()
                    Page(closeRecordingResponse(request(), closes), listOf(item), nextLink = next)
                },
                nextPage = { _, _ ->
                    val (item, next) = pages.removeFirst()
                    Page(closeRecordingResponse(request(), closes), listOf(item), nextLink = next)
                },
            )
        val collected = iterable.byPage().use { it.map { p -> p.items.single() } }
        assertEquals(listOf("p1", "p2"), collected)
        assertEquals(2, closes.get(), "the view (not the fetcher) closes each page response")
    }

    @Test
    fun `iterator flattens items across pages`() {
        val closes = AtomicInteger(0)
        val pages = ArrayDeque(listOf("a" to "n", "b" to null))
        val iterable =
            PagedIterable<String>(
                firstPage = {
                    val (i, n) = pages.removeFirst()
                    Page(closeRecordingResponse(request(), closes), listOf(i), nextLink = n)
                },
                nextPage = { _, _ ->
                    val (i, n) = pages.removeFirst()
                    Page(closeRecordingResponse(request(), closes), listOf(i), nextLink = n)
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
    // Behavior 2b: continuationToken drives the next fetch when nextLink is null
    // -------------------------------------------------------------------------

    @Test
    fun `continuationToken is used when nextLink is null and the next page is followed`() {
        val observedToken = arrayOfNulls<String>(1)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1), nextLink = null, continuationToken = "tok") },
                nextPage = { _, token ->
                    observedToken[0] = token
                    page(listOf(2))
                },
            )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals("tok", observedToken[0], "continuationToken must drive nextPage when nextLink is null")
    }

    // -------------------------------------------------------------------------
    // Behavior 2c: a null return from nextPage terminates iteration
    // -------------------------------------------------------------------------

    @Test
    fun `null return from nextPage terminates iteration after first page`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable =
            PagedIterable<Int>(
                firstPage = { page(listOf(1, 2), nextLink = "p2") },
                nextPage = { _, _ ->
                    nextPageCalls.incrementAndGet()
                    null
                },
            )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(1, nextPageCalls.get(), "nextPage must be called exactly once before its null ends the stream")
    }

    // -------------------------------------------------------------------------
    // Behavior 3: null first-page return yields nothing
    // -------------------------------------------------------------------------

    @Test
    fun `null firstPage return yields empty iteration`() {
        val iterable = PagedIterable<Int>(firstPage = { null })
        assertEquals(emptyList(), iterable.toList())
        assertEquals(emptyList<Page<Int>>(), iterable.byPage().use { it.toList() })
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
        iterable.byPage(opts).use { it.toList() }
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
        val run1 = iterable.byPage().use { it.map { p -> p.items } }
        val run2 = iterable.byPage().use { it.map { p -> p.items } }
        assertEquals(listOf(listOf(7, 8, 9)), run1)
        assertEquals(listOf(listOf(7, 8, 9)), run2)
        assertEquals(2, firstPageCalls.get(), "firstPage must be called once per byPage iteration")
    }

    @Test
    fun `byPage view is single-use`() {
        val iterable = PagedIterable<Int>(firstPage = { page(listOf(1)) })
        val view = iterable.byPage()
        view.iterator()
        assertFailsWith<IllegalStateException> { view.iterator() }
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
    // Behavior 10: stream() and byPage().stream() interop
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
    fun `byPage stream yields pages with correct item content and closes them`() {
        val closes = AtomicInteger(0)
        val pages: List<List<String>> =
            threePageIterable(closes).byPage().use { view ->
                view.stream().map { it.items }.collect(Collectors.toList())
            }
        assertEquals(listOf(listOf("p1"), listOf("p2"), listOf("p3")), pages)
        assertEquals(3, closes.get(), "byPage().stream() consumed fully must close all pages")
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
