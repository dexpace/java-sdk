/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.response.Response

/**
 * Items extractor shared by the paging strategies whose next-page state comes from somewhere other
 * than the items themselves: [PageNumberPaginationStrategy] (page number derived from the request
 * URL) and [LinkHeaderPaginationStrategy] (next-page URL taken from the `Link` header). Both need
 * only "read this page's items out of the response", so they share this single functional type.
 *
 * A dedicated functional interface (rather than a raw `(Response) -> List<T>` function type) gives
 * Java callers a clean SAM to implement instead of `kotlin.jvm.functions.Function1`. Kotlin callers
 * pass a lambda unchanged via SAM conversion.
 *
 * [CursorPaginationStrategy] intentionally does NOT use this type: its cursor lives in the same
 * payload as the items, so it reads both in one pass via [CursorExtractor] (returning a
 * [CursorResult]) to avoid a double-drain of the single-use body.
 *
 * @param T Element type read from the response body.
 */
public fun interface ItemsExtractor<T> {
    /** Reads the list of items from [response]. Must drain the response body synchronously. */
    public fun extract(response: Response): List<T>
}
