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
import java.util.stream.Stream

/**
 * Generic, strategy-driven paginator over an [HttpClient].
 *
 * A `Paginator` executes [initialRequest] against [httpClient], delegates response parsing
 * to [strategy], and exposes the result two ways: as a stream of items — [iterateAll] (a lazy
 * `Iterable<T>`) / [streamAll] (a Java 8 `Stream<T>`) — or as a stream of whole pages —
 * [byPage] (an auto-closing [CloseablePages] view), where each [Page] holds the live transport
 * [Response] plus its materialized items and per-page status/headers/request. Pagination state —
 * cursor, page number, link URL — is derived from each response by the strategy; the
 * paginator itself stays stateless.
 *
 * ## Laziness
 *
 * Iteration is page-lazy: a new page is fetched only when the consumer pulls past the
 * last item of the current page. Specifically, exactly one HTTP exchange happens per
 * page yielded. No prefetch, no batch fetch — empty pages count toward the fetch budget,
 * but advancing past a page whose strategy returned a null next request does not trigger a fetch.
 *
 * ## Termination
 *
 * The iterator terminates when either:
 *
 * - the current page's items are exhausted AND the strategy returned a `PageInfo` with a
 *   `null` next request (end of stream), or
 * - [maxPages] pages have already been fetched (the safety cap).
 *
 * ## Safety cap
 *
 * [maxPages] defaults to `Long.MAX_VALUE` (effectively unbounded, matching `Iterable`
 * semantics). A misbehaving server that returns the same `rel="next"` link — or any other
 * unchanging paging cursor — on every page would otherwise drive an unbounded fetch loop.
 * **Production callers should set a finite cap.** Once [maxPages] pages have been yielded the
 * iterator stops fetching, even if the strategy still reports a non-null next request. The cap
 * counts pages fetched (HTTP exchanges), not items.
 *
 * ## Response lifecycle
 *
 * Each [Page] retains the live [Response] so callers can inspect raw per-page status/headers
 * via [byPage]. Strategies MUST still read the body synchronously inside `parse(...)` (the body
 * is single-use, so they typically drain it to materialize the page's items) and MUST NOT retain
 * the response or its body past the call. The SDK closes each page's response for you: the item
 * views ([iterateAll]/[streamAll]) eager-close each page after copying its items, and [byPage]
 * returns an auto-closing [CloseablePages] view. The materialized items outlive the response. A
 * response is closed immediately if `parse(...)` throws, so a failing strategy never strands a
 * connection.
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

        /** Lazy iterable over all items across all pages. Each call restarts pagination. */
        public fun iterateAll(): Iterable<T> = Iterable { walker().items() }

        /** Sequential, ordered, unknown-size [Stream] over all items. */
        public fun streamAll(): Stream<T> = walker().itemStream()

        /**
         * Auto-closing page-level view exposing each page's live [Response] (raw status/headers).
         * Each call restarts pagination and returns a fresh single-use view; wrap it in `use { }` /
         * try-with-resources so an early break releases the held page (see [CloseablePages]).
         */
        public fun byPage(): CloseablePages<T> = CloseablePages(walker().pages())

        private fun walker(): PageWalker<T> {
            var nextRequest: Request? = initialRequest
            return PageWalker(
                source = {
                    val request = nextRequest ?: return@PageWalker null
                    nextRequest = null
                    val response: Response = httpClient.execute(request)
                    val info: PageInfo<T> =
                        try {
                            strategy.parse(response, initialRequest)
                        } catch (t: Throwable) {
                            response.close() // parse failed → this page never becomes a Page; release it now
                            throw t
                        }
                    nextRequest = info.nextRequest
                    Page(response, info.items)
                },
                maxPages = maxPages,
            )
        }
    }
