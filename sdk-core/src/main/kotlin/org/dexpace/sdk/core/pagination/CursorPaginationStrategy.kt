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
 * Cursor-based [PaginationStrategy]. The server returns an opaque cursor (typically in
 * the response body) which the client echoes back as a query parameter on the next
 * request.
 *
 * Wire shape:
 *
 * - Page-N request: `GET /things?cursor=<cursor-from-previous-page>`.
 * - Page-N response: arbitrary body containing both the items and the next cursor.
 * - End of stream: response carries a `null` or absent next cursor.
 *
 * The strategy is **stateless**: it relies on a single [extractor] to pull both the items
 * and the next cursor out of the response in one pass — see [CursorResult] — and rewrites the
 * initial request's URL, setting the cursor query parameter to the new value, for the next
 * page.
 *
 * ## Single read
 *
 * A response body is single-use. Because cursor APIs carry the items and the next cursor in
 * the same payload, the extractor reads the body **once** and returns both via a
 * [CursorResult]. This avoids the double-drain trap of pulling items and cursor with two
 * independent `(Response) -> …` lambdas, which forces either a failed second read or a
 * per-response cache to work around it.
 *
 * @param T Element type extracted from the response.
 * @property extractor Reads the items and next cursor from the response in a single pass.
 *   Called once per page; must drain the response body synchronously and return both pieces.
 * @property cursorQueryParam Query parameter name used to send the cursor (default
 *   `"cursor"`). Servers vary; common alternatives include `"after"`, `"next"`,
 *   `"pageCursor"`.
 */
public class CursorPaginationStrategy<T>
    @JvmOverloads
    constructor(
        private val extractor: (Response) -> CursorResult<T>,
        private val cursorQueryParam: String = "cursor",
    ) : PaginationStrategy<T> {
        override fun parse(
            response: Response,
            initialRequest: Request,
        ): Page<T> {
            val result: CursorResult<T> = extractor(response)
            val nextCursor: String? = result.nextCursor
            val hasNext: Boolean = !nextCursor.isNullOrEmpty()
            val nextRequest: Request? =
                if (hasNext) {
                    RequestRebuilder.withQueryParam(initialRequest, cursorQueryParam, nextCursor)
                } else {
                    null
                }
            return SimplePage(items = result.items, hasNext = hasNext, nextRequest = nextRequest)
        }
    }
