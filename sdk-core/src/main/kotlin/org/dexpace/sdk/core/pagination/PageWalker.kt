/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Internal lazy driver shared by [Paginator] and [PagedIterable]. Pulls pages from [source]
 * — which fully encapsulates its own paging state (cursor, next request, link) and returns
 * `null` at end of stream — and stops after [maxPages] pages. Page-lazy: [source] is invoked
 * only when the consumer pulls the next page. Also the single home for the iterator→`Stream`
 * adaptation, so the public wrappers do not each re-implement it.
 *
 * Page closing is split by view: [items] eager-closes each page before yielding any of its
 * items (so a partial item-consume never strands a connection), while [pages] yields **live**
 * pages and does NOT close them — the [CloseablePages] view that wraps [pages] owns page-level
 * closing.
 *
 * Each call to [pages] / [items] / [itemStream] consumes [source]; build a fresh walker (with a
 * fresh source) per iteration. A single returned iterator/stream is single-thread-only.
 */
internal class PageWalker<T>(
    private val source: () -> Page<T>?,
    private val maxPages: Long,
) {
    init {
        require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
    }

    /**
     * Lazy iterator over **live** pages: at most [maxPages], stopping early when [source] returns
     * `null`. Does NOT close pages — the caller (a [CloseablePages] view) owns page-level closing.
     */
    fun pages(): Iterator<Page<T>> =
        iterator {
            var count = 0L
            while (count < maxPages) {
                val page = source() ?: break
                yield(page)
                count++
            }
        }

    /**
     * Lazy iterator over items, flattening [Page.items] across pages. Each page is **eager-closed**
     * before any of its items are yielded (materialized [Page.items] survive close), so abandoning
     * the iteration mid-page never strands the page's response.
     */
    fun items(): Iterator<T> =
        iterator {
            val pageIterator = pages()
            while (pageIterator.hasNext()) {
                val page = pageIterator.next()
                val pageItems = page.items // materialized; survives close
                page.close() // close eagerly, before yielding any item
                yieldAll(pageItems)
            }
        }

    /** Sequential, ORDERED, unknown-size [Stream] over [items]. */
    fun itemStream(): Stream<T> = streamOf(items())

    private fun <E> streamOf(iterator: Iterator<E>): Stream<E> =
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
            false,
        )
}
