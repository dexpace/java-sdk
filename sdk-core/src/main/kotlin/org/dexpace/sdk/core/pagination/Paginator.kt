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

        /** Lazy iterable over all items across all pages. Each call restarts pagination. */
        public fun iterateAll(): Iterable<T> = Iterable { walker().items() }

        /** Sequential, ordered, unknown-size [Stream] over all items. */
        public fun streamAll(): Stream<T> = walker().itemStream()

        /** Lazy page-level view exposing per-page status/headers/items. Each call restarts pagination. */
        public fun byPage(): Iterable<Page<T>> = Iterable { walker().pages() }

        /** Sequential, ordered, unknown-size [Stream] over all pages. */
        public fun pageStream(): Stream<Page<T>> = walker().pageStream()

        private fun walker(): PageWalker<T> {
            var nextRequest: Request? = initialRequest
            return PageWalker(
                source = {
                    val request = nextRequest ?: return@PageWalker null
                    nextRequest = null
                    val response: Response = httpClient.execute(request)
                    try {
                        val info: PageInfo<T> = strategy.parse(response, initialRequest)
                        nextRequest = info.nextRequest
                        Page.from(response, info.items)
                    } finally {
                        response.close()
                    }
                },
                maxPages = maxPages,
            )
        }
    }
