/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request

/**
 * Output of [PaginationStrategy.parse]: the items on a page plus the request that fetches
 * the next page, or `null` when this is the last page.
 *
 * Strategies are stateless: everything needed to advance is derived from the response and
 * the initial-request template and returned here. A `null` [nextRequest] is the single
 * end-of-stream signal.
 *
 * @param T Element type carried in [items].
 * @property items Items on the page, in server order. Never `null`; may be empty.
 * @property nextRequest Request to execute for the following page, or `null` at end of stream.
 */
public class PageInfo<T>(
    public val items: List<T>,
    public val nextRequest: Request?,
)
