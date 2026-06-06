/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.paging

import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Fetches the first page of results for the given [PagingOptions]. Returns `null` to
 * signal that there are no results.
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 */
public fun interface FirstPageFetcher<T> {
    /**
     * Returns the first [PagedResponse] for [options], or `null` for an empty result set.
     */
    public fun fetch(options: PagingOptions): PagedResponse<T>?
}

/**
 * Fetches a subsequent page given the continuation token from the previous page.
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 */
public fun interface NextPageFetcher<T> {
    /**
     * Returns the next [PagedResponse] for [options] and [continuationToken], or `null`
     * to signal end of stream.
     */
    public fun fetch(
        options: PagingOptions,
        continuationToken: String,
    ): PagedResponse<T>?
}

/**
 * Lazy iteration over a paginated REST API.
 *
 * `PagedIterable<T>` flattens a sequence of pages into an `Iterable<T>` for callers that
 * only care about items, while [byPage] exposes the raw `Sequence<PagedResponse<T>>` for
 * callers that need page-level metadata (status, headers, links).
 *
 * Construction takes two function references:
 *
 * - [firstPage] — called once with the caller-supplied [PagingOptions] to fetch the first
 *   page. May return `null` to signal "no data".
 * - [nextPage] — called with the same [PagingOptions] and the link extracted from the
 *   previous page (either its [PagedResponse.nextLink] or [PagedResponse.continuationToken],
 *   whichever is present, with `nextLink` winning when both are set). May return `null` to
 *   signal end of stream. Default is `{ _, _ -> null }`, so single-page APIs can omit it.
 *
 * **Empty-page handling.** A page with `value.isEmpty()` is yielded as-is; the iterator
 * does **not** terminate just because the current page is empty. Termination is driven
 * purely by the link/token contract:
 *
 * - Both `nextLink` and `continuationToken` `null` → done.
 * - `nextLink` or `continuationToken` is an empty `String` (`""`) → done.
 * - Otherwise → call [nextPage]. If it returns `null`, done. Else yield and repeat.
 *
 * **Safety cap.** [maxPages] defaults to `Long.MAX_VALUE`. **Production callers should set
 * a finite cap** to defend against servers that return the same `nextLink` repeatedly. The
 * default is unbounded to match `Iterable` semantics; an explicit cap is the caller's
 * responsibility.
 *
 * **Fetch strategy.** Pages are fetched sequentially and lazily — no pre-fetch. The next
 * [nextPage] call only happens when the consumer pulls the next yield. Callers wanting
 * parallel pre-fetch must wrap this manually.
 *
 * **Iteration semantics.** Each call to [iterator] or [byPage] drives a fresh sequence
 * (matches the `Iterable` contract). Two concurrent iterators on the same instance maintain
 * independent state.
 *
 * **Stream interop.** [stream] returns a Java 8 `Stream<T>` built via `StreamSupport` over
 * the same iterator. No coroutine or third-party dependency is involved.
 *
 * @param T Element type yielded by [iterator].
 * @property firstPage Fetches the first page.
 * @property nextPage Fetches a subsequent page given the link from the previous page.
 * @property maxPages Defensive cap on the total number of pages yielded.
 */
public class PagedIterable<T>
    @JvmOverloads
    constructor(
        private val firstPage: FirstPageFetcher<T>,
        private val nextPage: NextPageFetcher<T> = NextPageFetcher { _, _ -> null },
        private val maxPages: Long = Long.MAX_VALUE,
    ) : Iterable<T> {
        /**
         * Returns a lazy sequence of pages, starting from the first page produced by
         * [firstPage]. Each subsequent page is fetched only when the consumer pulls.
         *
         * **Page ownership.** Each yielded [PagedResponse] is owned by the consumer; this method
         * does not close them. Consumers MUST `close()` each page after use (or wrap iteration
         * in `use {}` / try-with-resources) to release the underlying response body. The
         * flattening [iterator] path closes pages automatically — only direct callers of
         * [byPage] need to manage page lifecycle.
         *
         * [maxPages] defaults to `Long.MAX_VALUE`. **Production callers should set a finite
         * cap** to defend against servers that return the same `nextLink` repeatedly. The
         * default is unbounded to match `Iterable` semantics; an explicit cap is the caller's
         * responsibility.
         *
         * @param options Caller-supplied paging options forwarded to [firstPage] and
         *   [nextPage]. Default is a fresh, empty [PagingOptions].
         */
        @JvmOverloads
        public fun byPage(options: PagingOptions = PagingOptions()): Sequence<PagedResponse<T>> =
            sequence {
                var page: PagedResponse<T>? = firstPage.fetch(options)
                var pageCount = 0L
                while (page != null && pageCount < maxPages) {
                    yield(page)
                    pageCount++
                    if (pageCount >= maxPages) break // Cap reached: do not fetch a page we will not yield.
                    // Prefer the explicit `nextLink`; fall back to the continuation token.
                    // Treat an empty string as "no more pages" — servers sometimes serialize a
                    // missing cursor as `""` instead of omitting the field.
                    val link = page.nextLink ?: page.continuationToken
                    page = if (link.isNullOrEmpty()) null else nextPage.fetch(options, link)
                }
            }

        /**
         * Flattens all pages into an iterator of items. Each call starts a fresh iteration
         * (re-invokes [firstPage]).
         *
         * The iterator owns the pages it pulls through [byPage] and closes each one once its
         * items are exhausted — callers don't need to manage page lifecycle when iterating by
         * item.
         *
         * **Error propagation.** If `pages.next()` throws, the exception propagates immediately
         * to the caller. Because Kotlin's `AbstractIterator` transitions to FAILED state after
         * an exception escapes `computeNext`, subsequent calls to `hasNext()` throw
         * `IllegalArgumentException` from the AbstractIterator machinery. Callers that need
         * to detect the cause of the failure should catch the original exception on first throw.
         */
        override fun iterator(): Iterator<T> =
            object : AbstractIterator<T>() {
                private val pages = byPage().iterator()
                private var currentItems: Iterator<T>? = null
                private var currentPage: PagedResponse<T>? = null

                override fun computeNext() {
                    // Advance through pages until we find one with items left, or pages are exhausted.
                    while (true) {
                        val items = currentItems
                        if (items != null && items.hasNext()) {
                            setNext(items.next())
                            return
                        }
                        currentPage?.close()
                        currentPage = null
                        currentItems = null
                        if (!pages.hasNext()) {
                            done()
                            return
                        }
                        // Any exception from pages.next() propagates directly to the caller.
                        // AbstractIterator transitions to FAILED state, so further hasNext() calls
                        // raise IllegalArgumentException — this is the correct fail-fast behavior.
                        val next = pages.next()
                        currentPage = next
                        currentItems = next.value.iterator()
                    }
                }
            }

        /**
         * Java 8 `Stream<T>` view over the same iteration as [iterator]. Sequential,
         * `ORDERED`, unknown-size.
         */
        public fun stream(): Stream<T> {
            val spliterator =
                Spliterators.spliteratorUnknownSize(
                    iterator(),
                    Spliterator.ORDERED,
                )
            return StreamSupport.stream(spliterator, false)
        }
    }
