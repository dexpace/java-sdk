/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.request.Request

/**
 * A single page of items produced by a [PaginationStrategy].
 *
 * `Page` is the unit of work shared between a [Paginator] and its strategy. The paginator
 * receives a `Page<T>` per HTTP exchange, yields its [items], and consults [hasNext] +
 * [nextPageRequest] to decide whether (and how) to fetch the following page.
 *
 * ## Page metadata
 *
 * Beyond the items, a page may expose the HTTP envelope it was parsed from — [statusCode]
 * and [headers] — and the HATEOAS links / continuation token the server advertised
 * ([nextLink], [previousLink], [firstLink], [lastLink], [continuationToken]). These are the
 * page-level signals callers reach for when they need more than a flat item stream: logging
 * the status of each fetch, inspecting rate-limit headers, or rendering "first / previous /
 * next / last" navigation. All metadata accessors default to `null`, so a strategy that does
 * not know (or care about) a given signal can leave it unset; the bundled strategies populate
 * the links and envelope they parse.
 *
 * The [Paginator] closes the underlying response as soon as the strategy returns this page,
 * so a `Page` MUST NOT hold a live response or body — the metadata here is the deserialized,
 * response-independent view that survives the close.
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
     * HTTP status code of the response this page was parsed from, or `null` if the strategy
     * did not record it.
     */
    public val statusCode: Int? get() = null

    /**
     * Response headers this page was parsed from, or `null` if the strategy did not record
     * them. The headers are a snapshot taken before the response was closed.
     */
    public val headers: Headers? get() = null

    /**
     * Full URL of the next page (RFC 5988 `rel="next"` style), or `null` if absent or
     * unknown to the strategy. Distinct from [nextPageRequest], which is the actual request
     * the paginator drives — this is the raw advertised link for callers that want to
     * inspect or follow it themselves.
     */
    public val nextLink: String? get() = null

    /** Full URL of the previous page (`rel="prev"`), or `null` if absent/unknown. */
    public val previousLink: String? get() = null

    /** Full URL of the first page (`rel="first"`), or `null` if absent/unknown. */
    public val firstLink: String? get() = null

    /** Full URL of the last page (`rel="last"`), or `null` if absent/unknown. */
    public val lastLink: String? get() = null

    /**
     * Opaque continuation cursor the server issued for the next page, or `null` if the page
     * is not cursor-driven or the cursor is absent.
     */
    public val continuationToken: String? get() = null

    /**
     * Returns the [Request] the paginator should execute to fetch the page following this
     * one, or `null` if there is no next page. Implementations build the next request from
     * the initial-request template plus paging state (cursor, page number, link URL, …).
     *
     * @return The next page's request, or `null` if there is none.
     */
    public fun nextPageRequest(): Request?
}
