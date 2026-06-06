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
 * Token [PaginationStrategy]. Logically identical to [CursorPaginationStrategy] but with
 * a different naming convention — many APIs (Google Cloud, Slack, GitHub GraphQL legacy)
 * use the word *token* (`next_page_token`, `pageToken`) rather than *cursor*.
 *
 * Wire shape:
 *
 * - Page-N request: `GET /things?page_token=<token-from-previous-page>`.
 * - Page-N response: JSON body containing both the items and the next token.
 * - End of stream: response carries an empty / absent next token.
 *
 * Use this strategy when the token shape and naming differs from a typical "cursor"
 * scheme — primarily for readability at call sites and for the default [tokenQueryParam]
 * of `"page_token"`.
 *
 * @param T Element type extracted from the response.
 * @property itemsExtractor Reads the list of items from the response. Called once per
 *   page; must drain the response body synchronously.
 * @property tokenExtractor Reads the next-page token from the response, or returns
 *   `null` if there are no more pages.
 * @property tokenQueryParam Query parameter name used to send the token (default
 *   `"page_token"`).
 */
public class TokenPaginationStrategy<T>
    @JvmOverloads
    constructor(
        private val itemsExtractor: (Response) -> List<T>,
        private val tokenExtractor: (Response) -> String?,
        private val tokenQueryParam: String = "page_token",
    ) : PaginationStrategy<T> {
        override fun parse(
            response: Response,
            initialRequest: Request,
        ): Page<T> {
            val items: List<T> = itemsExtractor(response)
            val nextToken: String? = tokenExtractor(response)
            val hasNext: Boolean = !nextToken.isNullOrEmpty()
            val nextRequest: Request? =
                if (hasNext) {
                    RequestRebuilder.withQueryParam(initialRequest, tokenQueryParam, nextToken)
                } else {
                    null
                }
            return SimplePage(items = items, hasNext = hasNext, nextRequest = nextRequest)
        }
    }
