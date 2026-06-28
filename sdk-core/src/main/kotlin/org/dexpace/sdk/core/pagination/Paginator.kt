/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Generic, strategy-driven paginator over an [HttpClient].
 *
 * A `Paginator` executes [initialRequest] against [httpClient], delegates response parsing
 * to [strategy], and exposes the resulting stream of items via either [iterateAll] (a lazy
 * `Iterable<T>`) or [streamAll] (a Java 8 `Stream<T>`). Pagination state — cursor,
 * page number, link URL — is derived from each response by the strategy; the paginator
 * itself stays stateless.
 *
 * ## Laziness
 *
 * Iteration is page-lazy: a new page is fetched only when the consumer pulls past the
 * last item of the current page. Specifically, exactly one HTTP exchange happens per
 * page yielded. No prefetch, no batch fetch — empty pages count toward the fetch budget,
 * but advancing past an exhausted page with `hasNext = false` does not trigger a fetch.
 *
 * ## Termination
 *
 * The iterator terminates when either:
 *
 * - the current page's items are exhausted AND `page.hasNext == false`, or
 * - the current page's items are exhausted AND `page.nextPageRequest()` returned `null`, or
 * - [maxPages] pages have already been fetched (the safety cap).
 *
 * The first two conditions are semantically distinct but produce the same outcome: end of
 * stream.
 *
 * ## Safety cap
 *
 * [maxPages] defaults to `Long.MAX_VALUE` (effectively unbounded, matching `Iterable`
 * semantics). A misbehaving server that returns the same `rel="next"` link — or any other
 * unchanging paging cursor — on every page would otherwise drive an unbounded fetch loop.
 * **Production callers should set a finite cap.** Once [maxPages] pages have been yielded the
 * iterator stops fetching, even if the last page still reports `hasNext`. The cap counts
 * pages fetched (HTTP exchanges), not items.
 *
 * ## Response lifecycle
 *
 * Each [Response] returned by the transport is closed by the paginator after the strategy
 * has parsed it. Strategies MUST read everything they need from the response synchronously
 * inside `parse(...)`; they MUST NOT retain references to the response or its body past
 * the call. The items in the returned [Page] are owned by the strategy (typically already
 * deserialized into application objects) and outlive the response.
 *
 * ## Thread-safety
 *
 * `Paginator` itself is safe to share — it holds only immutable fields. Each call to
 * [iterateAll] or [streamAll] returns an independent iterator with its own state.
 * Concurrent iteration on a single iterator is unsafe; the iterator is intended for use
 * by one thread at a time.
 *
 * ## Java interop
 *
 * Java callers use this class directly:
 * ```
 * Paginator<Item> paginator = new Paginator<>(client, request, strategy);
 * for (Item item : paginator.iterateAll()) { ... }
 * paginator.streamAll().forEach(...);
 * ```
 *
 * @param T Element type yielded by the paginator.
 * @property httpClient Transport used to execute each page request.
 * @property initialRequest Request used to fetch the first page; also passed to the
 *   strategy as a template for building subsequent page requests.
 * @property strategy Strategy that parses each response into a [Page].
 * @property maxPages Safety cap on the total number of pages (HTTP exchanges) the iterator
 *   will fetch. Defaults to `Long.MAX_VALUE` (unbounded). Must be positive.
 */
public class Paginator<T>
    @JvmOverloads
    constructor(
        private val httpClient: HttpClient,
        private val initialRequest: Request,
        private val strategy: PaginationStrategy<T>,
        private val maxPages: Long = Long.MAX_VALUE,
    ) {
        init {
            require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
        }

        /**
         * Returns an [Iterable] that lazily walks every item across every page.
         *
         * Each call returns a **fresh** iterable — re-iterating restarts pagination from
         * [initialRequest]. The iterable is single-pass per iterator (calling `iterator()`
         * twice yields two independent iterators that each re-execute requests).
         *
         * @return Lazy iterable over all items.
         */
        public fun iterateAll(): Iterable<T> =
            Iterable {
                PaginatorIterator(httpClient, initialRequest, strategy, maxPages)
            }

        /**
         * Returns a sequential, ordered, unknown-size Java 8 [Stream] of every item across
         * every page. Backed by the same iterator as [iterateAll], so the same laziness and
         * lifecycle semantics apply.
         *
         * @return Lazy stream over all items.
         */
        public fun streamAll(): Stream<T> {
            val spliterator =
                Spliterators.spliteratorUnknownSize(
                    iterateAll().iterator(),
                    Spliterator.ORDERED,
                )
            return StreamSupport.stream(spliterator, false)
        }

        /**
         * Single-thread iterator over all items across all pages.
         *
         * Holds the in-progress page and item index; on `hasNext()`, advances to the next
         * page (executing exactly one HTTP request) when the current page is exhausted and
         * the page reports `hasNext` and produces a non-null next request. Stops fetching
         * once [maxPages] pages have been pulled.
         */
        private class PaginatorIterator<T>(
            private val httpClient: HttpClient,
            private val initialRequest: Request,
            private val strategy: PaginationStrategy<T>,
            private val maxPages: Long,
        ) : Iterator<T> {
            // null = no page fetched yet; the first hasNext() call triggers the initial fetch.
            private var currentPage: PageInfo<T>? = null

            // Index into currentPage.items for the next item to emit.
            private var currentItemIndex: Int = 0

            // The next request to fetch: seeded with initialRequest, then replaced after each page
            // with that page's nextPageRequest(), or null once a page reports no successor (which
            // ends the stream on the following advance()).
            private var nextRequest: Request? = initialRequest

            // true after iteration is definitively over; prevents further fetches.
            private var done: Boolean = false

            // Number of pages (HTTP exchanges) fetched so far. Iteration stops once this reaches
            // maxPages, guarding against servers that never advance their paging cursor.
            private var pagesFetched: Long = 0L

            override fun hasNext(): Boolean {
                while (true) {
                    val page = currentPage
                    if (page != null && currentItemIndex < page.items.size) {
                        return true
                    }
                    if (done) {
                        return false
                    }
                    if (!advance()) {
                        return false
                    }
                }
            }

            override fun next(): T {
                if (!hasNext()) {
                    throw NoSuchElementException("Paginator iterator exhausted.")
                }
                val page = currentPage!!
                val item = page.items[currentItemIndex]
                currentItemIndex++
                return item
            }

            /**
             * Fetches the next page. Returns `true` if a new page was loaded into
             * [currentPage]; `false` if iteration is now done.
             */
            private fun advance(): Boolean {
                val request = nextRequest
                if (request == null || pagesFetched >= maxPages) {
                    // Either no next request is scheduled (the previous page had no successor) or
                    // the safety cap is reached — stop before fetching a page we would otherwise
                    // yield, even if the previous page still reports hasNext.
                    done = true
                    currentPage = null
                    return false
                }
                val response: Response = httpClient.execute(request)
                pagesFetched++
                val info: PageInfo<T> =
                    try {
                        strategy.parse(response, initialRequest)
                    } finally {
                        response.close()
                    }
                currentPage = info
                currentItemIndex = 0
                // If this page has no next request, nextRequest goes null; the next advance() ends the stream.
                nextRequest = info.nextRequest
                return true
            }
        }
    }
