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
 * exposes [byPage] for callers that want page-level metadata and raw per-page [Response] access.
 * Each [Page] holds the live transport [Response]; the SDK closes it for you — item-level views
 * eager-close each page, and [byPage] returns an auto-closing [CloseablePages] view.
 *
 * Construction takes two fetchers: [firstPage] (called once) and [nextPage] (called with the
 * previous page's [Page.nextLink], falling back to [Page.continuationToken], with `nextLink`
 * winning; an empty/blank link ends the stream). Each fetcher builds a `Page` that **owns** its
 * `Response`: hand the live response to `Page(response, items, …)` and do NOT close it yourself
 * (no `use { }` in the fetcher) — the SDK releases it via the item path or the [CloseablePages]
 * view. A fetcher that throws before building the page is still responsible for that response.
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

        /**
         * Auto-closing page-level view exposing each page's live [Response]. Each call returns a
         * fresh single-use view and restarts pagination. Wrap it in `use { }` / try-with-resources
         * so an early break releases the held page (see [CloseablePages]).
         */
        @JvmOverloads
        public fun byPage(options: PagingOptions = PagingOptions()): CloseablePages<T> =
            CloseablePages(walker(options).pages())
    }
