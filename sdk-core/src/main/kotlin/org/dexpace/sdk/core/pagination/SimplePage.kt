/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request

/**
 * Default [Page] implementation used by the bundled strategies.
 *
 * Internal because consumers should not depend on this concrete type — they receive a
 * [Page] from a [PaginationStrategy] and use it through that interface. Strategies built
 * outside `sdk-core` are free to provide their own `Page` implementations.
 */
internal class SimplePage<T>(
    override val items: List<T>,
    override val hasNext: Boolean,
    private val nextRequest: Request?,
) : Page<T> {
    override fun nextPageRequest(): Request? = nextRequest
}
