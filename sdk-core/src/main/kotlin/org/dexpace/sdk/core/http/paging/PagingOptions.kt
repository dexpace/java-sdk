/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.paging

/**
 * Mutable carrier for paging request parameters.
 *
 * Covers the three common addressing modes used by REST APIs:
 *
 * - **Offset-based** ([offset] + optional [pageSize]) — e.g. `?offset=20&limit=10`.
 * - **Index-based** ([pageIndex] + optional [pageSize]) — e.g. `?page=2&size=10`.
 * - **Token-based** ([continuationToken]) — opaque server-issued cursor, e.g. `?cursor=abc123`.
 *
 * All fields are nullable so a caller can opt in to a subset. Fields are `var` because
 * paging options are commonly mutated as iteration progresses (e.g. a custom retriever may
 * stash the latest continuation token here before requesting the next page).
 *
 * Instances are **not** thread-safe.
 *
 * ## Mutation visibility
 *
 * [PagedIterable] passes the **same** `PagingOptions` instance to every `nextPage` invocation —
 * mutations performed inside one `nextPage` lambda (for example, updating [continuationToken]
 * with the cursor returned by the previous page) are observable by all subsequent
 * `firstPage` / `nextPage` calls during the iteration. The caller is responsible for choosing
 * a coherent mutation strategy (read the cursor, mutate `options`, return the page).
 *
 * @property offset Zero-based starting index of the first item the server should return.
 * @property pageSize Maximum number of items the server should return per page.
 * @property pageIndex Zero-based page number to fetch (mutually exclusive with [offset] in
 *   most server contracts; the server defines precedence).
 * @property continuationToken Opaque server-issued cursor to fetch the next page. Mutually
 *   exclusive with [offset] / [pageIndex] on most servers.
 */
public class PagingOptions
    @JvmOverloads
    constructor(
        public var offset: Long? = null,
        public var pageSize: Long? = null,
        public var pageIndex: Long? = null,
        public var continuationToken: String? = null,
    )
