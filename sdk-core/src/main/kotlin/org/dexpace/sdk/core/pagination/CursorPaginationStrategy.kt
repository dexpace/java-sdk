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
 * The strategy is **stateless**: it relies on [itemsExtractor] and [cursorExtractor]
 * lambdas to pull data out of the response, and on [RequestRebuilder] to mutate the
 * initial request's URL with the new cursor query parameter.
 *
 * @param T Element type extracted from the response.
 * @property itemsExtractor Reads the list of items from the response. Called once per
 *   page; must drain the response body synchronously.
 * @property cursorExtractor Reads the next cursor from the response, or returns `null`
 *   if there are no more pages.
 * @property cursorQueryParam Query parameter name used to send the cursor (default
 *   `"cursor"`). Servers vary; common alternatives include `"after"`, `"next"`,
 *   `"pageCursor"`.
 */
public class CursorPaginationStrategy<T>
    @JvmOverloads
    constructor(
        private val itemsExtractor: (Response) -> List<T>,
        private val cursorExtractor: (Response) -> String?,
        private val cursorQueryParam: String = "cursor",
    ) : PaginationStrategy<T> {
        override fun parse(
            response: Response,
            initialRequest: Request,
        ): Page<T> {
            val items: List<T> = itemsExtractor(response)
            val nextCursor: String? = cursorExtractor(response)
            val hasNext: Boolean = !nextCursor.isNullOrEmpty()
            val nextRequest: Request? =
                if (hasNext) {
                    RequestRebuilder.withQueryParam(initialRequest, cursorQueryParam, nextCursor)
                } else {
                    null
                }
            return SimplePage(items = items, hasNext = hasNext, nextRequest = nextRequest)
        }
    }
