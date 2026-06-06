/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request

/**
 * A single page of items produced by a [PaginationStrategy].
 *
 * `Page` is the unit of work shared between a [Paginator] and its strategy. The paginator
 * receives a `Page<T>` per HTTP exchange, yields its [items], and consults [hasNext] +
 * [nextPageRequest] to decide whether (and how) to fetch the following page.
 *
 * ## Thread-safety
 *
 * Implementations must be immutable and safe to share across threads. Strategies typically
 * implement this interface with simple data-class-style holders.
 *
 * ## Empty-page handling
 *
 * A `Page` MAY have `items.isEmpty()` and `hasNext = true` — the strategy decides whether
 * an empty payload signals "more pages may follow" or "end of stream". The paginator does
 * **not** terminate on an empty page; it terminates when either [hasNext] is `false` or
 * [nextPageRequest] returns `null`.
 *
 * @param T Element type carried in [items].
 */
public interface Page<T> {
    /**
     * Items on this page, in server-defined order. Never `null`; may be empty.
     */
    public val items: List<T>

    /**
     * Indicates whether the paginator should attempt to fetch another page. Strategies
     * compute this from the response (e.g. presence of a next cursor, a `rel="next"` link,
     * a non-empty page).
     */
    public val hasNext: Boolean

    /**
     * Returns the [Request] the paginator should execute to fetch the page following this
     * one, or `null` if there is no next page. Implementations build the next request from
     * the initial-request template plus paging state (cursor, page number, link URL, …).
     *
     * @return The next page's request, or `null` if there is none.
     */
    public fun nextPageRequest(): Request?
}
