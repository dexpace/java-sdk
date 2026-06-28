/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageWalkerTest {
    private fun page(vararg items: String): Page<String> =
        Page(items = items.toList(), statusCode = 200, headers = Headers.builder().build(), request = null)

    @Test
    fun `walks until source returns null and flattens items`() {
        val pages = ArrayDeque(listOf(page("a", "b"), page("c"), page()))
        val walker = PageWalker({ pages.removeFirstOrNull() }, Long.MAX_VALUE)
        assertEquals(listOf("a", "b", "c"), walker.items().asSequence().toList())
    }

    @Test
    fun `maxPages caps the number of pages yielded`() {
        var produced = 0
        val walker =
            PageWalker({
                produced++
                page("x$produced")
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
