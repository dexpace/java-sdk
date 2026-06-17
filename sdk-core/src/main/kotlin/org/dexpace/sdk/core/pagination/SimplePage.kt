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
 * Default [Page] implementation used by the bundled strategies.
 *
 * Internal because consumers should not depend on this concrete type — they receive a
 * [Page] from a [PaginationStrategy] and use it through that interface. Strategies built
 * outside `sdk-core` are free to provide their own `Page` implementations.
 *
 * Carries the optional page metadata ([statusCode], [headers], and the HATEOAS links /
 * continuation token) so the bundled strategies can surface the response envelope they
 * already parsed. Each defaults to `null` for strategies that do not record a given signal.
 */
internal class SimplePage<T>(
    override val items: List<T>,
    override val hasNext: Boolean,
    private val nextRequest: Request?,
    override val statusCode: Int? = null,
    override val headers: Headers? = null,
    override val nextLink: String? = null,
    override val previousLink: String? = null,
    override val firstLink: String? = null,
    override val lastLink: String? = null,
    override val continuationToken: String? = null,
) : Page<T> {
    override fun nextPageRequest(): Request? = nextRequest
}
