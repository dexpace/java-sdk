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
 * Each call to [pages] / [items] / [pageStream] / [itemStream] consumes [source]; build a
 * fresh walker (with a fresh source) per iteration. A single returned iterator/stream is
 * single-thread-only.
 */
internal class PageWalker<T>(
    private val source: () -> Page<T>?,
    private val maxPages: Long,
) {
    init {
        require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
    }

    /** Lazy iterator over pages: at most [maxPages], stopping early when [source] returns `null`. */
    fun pages(): Iterator<Page<T>> =
        iterator {
            var count = 0L
            while (count < maxPages) {
                val page = source() ?: break
                yield(page)
                count++
            }
        }

    /** Lazy iterator over items, flattening [Page.items] across pages. */
    fun items(): Iterator<T> =
        iterator {
            val pageIterator = pages()
            while (pageIterator.hasNext()) {
                yieldAll(pageIterator.next().items)
            }
        }

    /** Sequential, ORDERED, unknown-size [Stream] over [pages]. */
    fun pageStream(): Stream<Page<T>> = streamOf(pages())

    /** Sequential, ORDERED, unknown-size [Stream] over [items]. */
    fun itemStream(): Stream<T> = streamOf(items())

    private fun <E> streamOf(iterator: Iterator<E>): Stream<E> =
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
            false,
        )
}
