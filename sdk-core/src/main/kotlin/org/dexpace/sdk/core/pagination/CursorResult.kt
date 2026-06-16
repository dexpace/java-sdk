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
 * Instances are immutable and safe to share, provided [items] is not mutated by the caller
 * after construction. The bundled extractor path always builds a `CursorResult` over a list
 * the extractor owns.
 *
 * @param T Element type carried in [items].
 * @property items Items on the page, in server-defined order. Never `null`; may be empty.
 * @property nextCursor Opaque cursor to send on the next request, or `null` (or empty) when
 *   the response signals end-of-stream.
 */
public class CursorResult<out T>(
    public val items: List<T>,
    public val nextCursor: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CursorResult<*>) return false
        return items == other.items && nextCursor == other.nextCursor
    }

    override fun hashCode(): Int = 31 * items.hashCode() + (nextCursor?.hashCode() ?: 0)

    override fun toString(): String = "CursorResult(items=$items, nextCursor=$nextCursor)"
}
