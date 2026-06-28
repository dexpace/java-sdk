/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import java.util.stream.Stream

/** Fetches the first [Page], or `null` for an empty result set. */
public fun interface FirstPageFetcher<T> {
    public fun fetch(options: PagingOptions): Page<T>?
}

/** Fetches a subsequent [Page] given the link/token from the previous page, or `null` at end of stream. */
public fun interface NextPageFetcher<T> {
    public fun fetch(
        options: PagingOptions,
        continuationToken: String,
    ): Page<T>?
}

/**
 * Lazy, transport-agnostic iteration over a paginated API.
 *
 * `PagedIterable<T>` flattens pages into an `Iterable<T>` for callers that want items, and
 * exposes [byPage] / [pageStream] for callers that want page-level metadata. Pages are pure
 * values ([Page] holds no open response), so every view is leak-free and [byPage] can be
 * collected freely.
 *
 * Construction takes two fetchers: [firstPage] (called once) and [nextPage] (called with the
 * previous page's [Page.nextLink], falling back to [Page.continuationToken], with `nextLink`
 * winning; an empty/blank link ends the stream). The fetcher owns the `Response` it reads —
 * build the page with [Page.from] inside `response.use { … }` so the connection is released.
 *
 * [maxPages] defaults to `Long.MAX_VALUE`; production callers should set a finite cap.
 */
public class PagedIterable<T>
    @JvmOverloads
    constructor(
        private val firstPage: FirstPageFetcher<T>,
        private val nextPage: NextPageFetcher<T> = NextPageFetcher { _, _ -> null },
        private val maxPages: Long = Long.MAX_VALUE,
    ) : Iterable<T> {
        init {
            require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
        }

        private fun walker(options: PagingOptions): PageWalker<T> {
            var started = false
            var previous: Page<T>? = null
            return PageWalker(
                source = {
                    val page: Page<T>? =
                        if (!started) {
                            started = true
                            firstPage.fetch(options)
                        } else {
                            val prev = previous
                            val link = prev?.nextLink ?: prev?.continuationToken
                            if (link.isNullOrEmpty()) null else nextPage.fetch(options, link)
                        }
                    previous = page
                    page
                },
                maxPages = maxPages,
            )
        }

        override fun iterator(): Iterator<T> = walker(PagingOptions()).items()

        /** Sequential, ordered, unknown-size [Stream] of every item. */
        public fun stream(): Stream<T> = walker(PagingOptions()).itemStream()

        /** Lazy page-level view. Each [iterator] call restarts pagination. Pages are pure values. */
        @JvmOverloads
        public fun byPage(options: PagingOptions = PagingOptions()): Iterable<Page<T>> =
            Iterable { walker(options).pages() }

        /** Sequential, ordered, unknown-size [Stream] of every [Page]. */
        @JvmOverloads
        public fun pageStream(options: PagingOptions = PagingOptions()): Stream<Page<T>> =
            walker(options).pageStream()
    }
