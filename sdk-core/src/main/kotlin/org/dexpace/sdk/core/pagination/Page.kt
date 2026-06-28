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

/**
 * A single, fully-materialized page of results.
 *
 * `Page` is an immutable value: its [items] are already drained from the response body, and
 * its metadata ([statusCode], [headers], [request]) is snapshotted at construction. A `Page`
 * holds **no** open `Response` and is **not** `Closeable` — there is nothing to leak. Paging
 * metadata covers HATEOAS links ([nextLink], [previousLink], [firstLink], [lastLink]) and an
 * opaque [continuationToken]; strategy-produced pages leave the link/token fields `null`
 * (the strategy folds that state into the next request) and expose [headers] for raw access.
 *
 * @param T Element type carried in [items].
 */
public class Page<T>
    @JvmOverloads
    constructor(
        public val items: List<T>,
        public val statusCode: Int,
        public val headers: Headers,
        public val request: Request?,
        public val continuationToken: String? = null,
        public val nextLink: String? = null,
        public val previousLink: String? = null,
        public val firstLink: String? = null,
        public val lastLink: String? = null,
    ) {
        public companion object {
            /**
             * Builds a [Page] by snapshotting [statusCode][Page.statusCode], [headers] and
             * [request] from [response]. Does **not** retain or close [response]; the caller
             * owns its lifecycle (the SDK's own drivers close it immediately after this call).
             */
            @JvmStatic
            @JvmOverloads
            public fun <T> from(
                response: Response,
                items: List<T>,
                continuationToken: String? = null,
                nextLink: String? = null,
                previousLink: String? = null,
                firstLink: String? = null,
                lastLink: String? = null,
            ): Page<T> =
                Page(
                    items = items,
                    statusCode = response.status.code,
                    headers = response.headers,
                    request = response.request,
                    continuationToken = continuationToken,
                    nextLink = nextLink,
                    previousLink = previousLink,
                    firstLink = firstLink,
                    lastLink = lastLink,
                )
        }
    }
