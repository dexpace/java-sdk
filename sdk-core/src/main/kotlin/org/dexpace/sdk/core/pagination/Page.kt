/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.Closeable
import java.io.IOException

/**
 * A single page of results that holds the live transport [Response].
 *
 * `Page` carries two kinds of state with different lifetimes:
 *
 * - **Materialized/derived state that survives [close].** [items] are already drained from the
 *   response body into a `List<T>`, and [statusCode], [headers] and [request] are derived from
 *   the [Response] object (which outlives [close] — closing only releases the body/connection).
 *   All of these remain readable after [close].
 * - **The raw [response] body/connection, valid only until [close].** Reading [response] (its
 *   body stream, in particular) is only safe while the page is "current"; once the page is
 *   closed the body/connection is released.
 *
 * Paging metadata covers HATEOAS links ([nextLink], [previousLink], [firstLink], [lastLink])
 * and an opaque [continuationToken]; strategy-produced pages leave the link/token fields `null`
 * (the strategy folds that state into the next request).
 *
 * ## Ownership and closing
 *
 * Whoever pulls a `Page` owns closing it. The SDK handles this for you on both common paths:
 *
 * - **Item-level** iteration ([PagedIterable.iterator], [Paginator.iterateAll], the `stream`
 *   variants) closes each page automatically — it copies the materialized [items], closes the
 *   page, then yields the items, so item consumers carry no close burden and a partial consume
 *   never strands a connection.
 * - **Page-level** iteration ([PagedIterable.byPage] / [Paginator.byPage]) returns an
 *   auto-closing [CloseablePages] view; wrap it in `use { }` / try-with-resources so an early
 *   break still releases the held page.
 *
 * A `Page` handed to you directly (e.g. by a [FirstPageFetcher]) is owned by the SDK once built;
 * fetchers must NOT close the [Response] themselves.
 *
 * @param T Element type carried in [items].
 * @property response Live transport response backing this page; its body/connection is valid
 *   only until [close].
 * @property items Items on the page, fully materialized; survive [close]. Never `null`; may be empty.
 * @property continuationToken Opaque cursor for the next page, or `null`.
 * @property nextLink HATEOAS `rel="next"` link, or `null`.
 * @property previousLink HATEOAS `rel="prev"` link, or `null`.
 * @property firstLink HATEOAS `rel="first"` link, or `null`.
 * @property lastLink HATEOAS `rel="last"` link, or `null`.
 */
public class Page<T>
    @JvmOverloads
    constructor(
        public val response: Response,
        public val items: List<T>,
        public val continuationToken: String? = null,
        public val nextLink: String? = null,
        public val previousLink: String? = null,
        public val firstLink: String? = null,
        public val lastLink: String? = null,
    ) : Closeable {
        /** HTTP status code, derived from [response]; survives [close]. */
        public val statusCode: Int get() = response.status.code

        /** Response headers, derived from [response]; survive [close]. */
        public val headers: Headers get() = response.headers

        /** Request that produced this page, derived from [response]; survives [close]. */
        public val request: Request get() = response.request

        /**
         * Releases the underlying [response] body/connection. [items], [statusCode], [headers]
         * and [request] remain readable afterwards; only the raw [response] body becomes invalid.
         *
         * @throws IOException If the response's close fails.
         */
        @Throws(IOException::class)
        override fun close() {
            response.close()
        }
    }
