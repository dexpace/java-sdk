/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.response.Response

/**
 * Result of a single-pass cursor extraction: the items on a page together with the cursor
 * for the next page, pulled from one read of the [Response].
 *
 * Cursor APIs return both the items and the next cursor in the same payload, so reading
 * them with two separate `(Response) -> …` lambdas means draining the body twice. A response
 * body is single-use, so the second drain either fails or — at best — is worked around with
 * a per-response cache. `CursorResult` lets a [CursorPaginationStrategy] extractor parse the
 * response once and hand back both pieces, keeping the public API honest about the
 * single-read constraint.
 *
 * ## Thread-safety
 *
 * The [items] list passed at construction is stored by reference, so a caller that retains a
 * mutable list can mutate it afterwards and the change will be visible through this result.
 * The bundled extractor path always builds a fresh list per page, so values produced by the
 * SDK are effectively immutable; hand-built results that share a mutable list should copy it
 * (`items.toList()`) before constructing.
 *
 * @param T Element type carried in [items].
 * @property items Items on the page, in server-defined order. Never `null`; may be empty.
 * @property nextCursor Opaque cursor to send on the next request, or `null` (or empty) when
 *   the response signals end-of-stream.
 */
public data class CursorResult<out T>(
    public val items: List<T>,
    public val nextCursor: String?,
)
