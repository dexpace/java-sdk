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
 * Page-number [PaginationStrategy]. The client sends a 1-based (or [startPage]-based)
 * page number as a query parameter; the server returns the corresponding slice of items.
 *
 * Wire shape:
 *
 * - Page-1 request: `GET /things` (or `GET /things?page=1` — both supported).
 * - Page-N request: `GET /things?page=N`.
 * - Page-N response: arbitrary body containing the items for page N.
 * - End of stream: response carries an empty items list. The strategy treats an empty
 *   page as the terminator.
 *
 * The strategy is **stateless**: the current page number is inferred from the response's
 * originating request URL (via the [pageParam] query parameter) — if the parameter is
 * absent, the implied page is [startPage]; otherwise it is parsed from the URL. The next
 * page number is `current + 1`.
 *
 * @param T Element type extracted from the response.
 * @property itemsExtractor Reads the list of items from the response. Called once per
 *   page; must drain the response body synchronously.
 * @property pageParam Query parameter name used to send the page number (default
 *   `"page"`). Common alternatives include `"pageNumber"` and `"p"`.
 * @property startPage First page number sent to the server (default `1`). Some APIs use
 *   `0` for the first page.
 */
public class PageNumberPaginationStrategy<T>
    @JvmOverloads
    constructor(
        private val itemsExtractor: (Response) -> List<T>,
        private val pageParam: String = "page",
        private val startPage: Int = 1,
    ) : PaginationStrategy<T> {
        override fun parse(
            response: Response,
            initialRequest: Request,
        ): PageInfo<T> {
            val items: List<T> = itemsExtractor(response)
            // Empty page → end of stream. Defensive against servers that "succeed" with an
            // empty list rather than 404 / a sentinel field once you've paged off the end.
            if (items.isEmpty()) {
                return PageInfo(items = items, nextRequest = null)
            }
            val executedUrl = response.request.url
            val executedPageParam: String? = RequestRebuilder.getQueryParam(executedUrl, pageParam)
            val executedPage: Int = parsePageOrDefault(executedPageParam, startPage)
            val nextPage: Int = executedPage + 1
            val nextRequest: Request =
                RequestRebuilder.withQueryParam(initialRequest, pageParam, nextPage.toString())
            return PageInfo(items = items, nextRequest = nextRequest)
        }

        private fun parsePageOrDefault(
            raw: String?,
            fallback: Int,
        ): Int =
            // Null, empty, or a non-numeric "page" param echoed back by the server all fall back
            // rather than crash.
            raw?.toIntOrNull() ?: fallback
    }
