/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageWalkerTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun request(): Request =
        Request.builder().url(URL("https://api.example.com/items")).method(Method.GET).build()

    private fun page(
        closeCount: AtomicInteger,
        vararg items: String,
    ): Page<String> = Page(closeRecordingResponse(request(), closeCount), items.toList())

    @Test
    fun `walks until source returns null and flattens items`() {
        val closes = AtomicInteger(0)
        val pages = ArrayDeque(listOf(page(closes, "a", "b"), page(closes, "c"), page(closes)))
        val walker = PageWalker({ pages.removeFirstOrNull() }, Long.MAX_VALUE)
        assertEquals(listOf("a", "b", "c"), walker.items().asSequence().toList())
    }

    @Test
    fun `items eager-closes every page it walks`() {
        val closes = AtomicInteger(0)
        val pages = ArrayDeque(listOf(page(closes, "a", "b"), page(closes, "c"), page(closes)))
        val walker = PageWalker({ pages.removeFirstOrNull() }, Long.MAX_VALUE)
        walker.items().asSequence().toList()
        assertEquals(3, closes.get(), "items() must close each of the 3 pages it walked")
    }

    @Test
    fun `items eager-closes the consumed page on a partial consume`() {
        val closes = AtomicInteger(0)
        // A second page that, if ever fetched, would prove we over-walked.
        val secondFetched = AtomicInteger(0)
        val walker =
            PageWalker<String>(
                {
                    when (closes.get() + secondFetched.get()) {
                        0 -> page(closes, "first", "second")
                        else -> {
                            secondFetched.incrementAndGet()
                            page(closes, "later")
                        }
                    }
                },
                Long.MAX_VALUE,
            )
        // Take only the first item: the producing page is fully materialized and must already be closed.
        val first = walker.items().asSequence().first()
        assertEquals("first", first)
        assertEquals(1, closes.get(), "the consumed page must be closed before its items are yielded")
        assertEquals(0, secondFetched.get(), "a partial consume must not fetch the next page")
    }

    @Test
    fun `pages does NOT close pages -- the view owns page-level closing`() {
        val closes = AtomicInteger(0)
        val pages = ArrayDeque(listOf(page(closes, "a"), page(closes, "b")))
        val walker = PageWalker({ pages.removeFirstOrNull() }, Long.MAX_VALUE)
        val walked = walker.pages().asSequence().toList()
        assertEquals(2, walked.size)
        assertEquals(0, closes.get(), "pages() must yield live pages and never close them")
    }

    @Test
    fun `maxPages caps the number of pages yielded`() {
        var produced = 0
        val closes = AtomicInteger(0)
        val walker =
            PageWalker({
                produced++
                page(closes, "x$produced")
            }, maxPages = 2)
        val collected = walker.pages().asSequence().toList()
        assertEquals(2, collected.size)
        assertEquals(2, produced) // never fetches a third page
    }

    @Test
    fun `rejects non-positive maxPages`() {
        assertFailsWith<IllegalArgumentException> { PageWalker<String>({ null }, 0) }
    }
}
