/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response

/**
 * Parses a [Response] into a [Page] and uses the [initialRequest] as a template for
 * building the next page's request.
 *
 * `PaginationStrategy` is the seam that lets the [Paginator] support any wire convention —
 * cursor-in-body, page-number-in-query, `Link` headers, opaque tokens — without changing
 * the surrounding iteration machinery. Implementations are **stateless**: they receive
 * everything they need (the response and the request template) as arguments. State that
 * varies across pages (cursor values, page numbers, next URLs) is derived from the
 * response and re-injected into the template by the strategy's `parse` call.
 *
 * The single-method shape means strategies are SAM-friendly — Kotlin callers may pass a
 * lambda, Java callers may pass a lambda or anonymous class, and both can compose
 * strategies functionally.
 *
 * ## Thread-safety
 *
 * Implementations must be immutable and safe to share across threads. A single strategy
 * instance may back many concurrent paginators.
 *
 * @param T Element type the strategy extracts from each response.
 */
public fun interface PaginationStrategy<T> {
    /**
     * Parses [response] into a [Page] of items. Uses [initialRequest] as a template for
     * the next page's URL/headers/method/body, mutating only what the wire convention
     * demands (e.g. swapping the cursor query param, replacing the URL with a
     * `Link: rel="next"` value).
     *
     * Implementations are expected to be pure with respect to their own state — they may
     * read the response body, but they MUST NOT mutate it or close it. The paginator owns
     * the response lifecycle.
     *
     * @param response Response returned by the transport for the previous page's request.
     * @param initialRequest Request template used to build the first page; reused as the
     *   scaffold for subsequent pages so headers/method/body are preserved.
     * @return The parsed [PageInfo]. Never `null`; a `null` [PageInfo.nextRequest] signals
     *   end of stream.
     */
    public fun parse(
        response: Response,
        initialRequest: Request,
    ): PageInfo<T>
}
