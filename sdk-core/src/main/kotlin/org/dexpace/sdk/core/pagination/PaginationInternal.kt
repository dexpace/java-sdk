/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

/*
 * Shared internal helpers for the `pagination` package, factored out so the sync and async
 * paginators, the walker, and the page-level view do not each re-implement the same one-liners
 * (and cannot silently diverge on the invariants those one-liners encode).
 */

/**
 * Rejects a non-positive [maxPages] with the message the public paginator wrappers share. Each
 * wrapper checks eagerly in its own `init` so the failure surfaces when the object is built, not
 * lazily on first walk; centralising the message keeps those checks identical.
 */
@JvmSynthetic
internal fun requirePositiveMaxPages(maxPages: Long) {
    require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
}

/**
 * Adapts [iterator] into a sequential, ORDERED, unknown-size [Stream] — the single home for the
 * iterator→`Stream` boilerplate the pagination views share ([PageWalker.itemStream] and
 * [CloseablePages.stream]). When [onClose] is supplied it is registered as the stream's close
 * handler, so closing the returned `Stream` (try-with-resources / `use { }`) runs it — the seam
 * a page-level view uses to release a page a short-circuiting terminal left held open.
 */
@JvmSynthetic
internal fun <E> orderedStream(
    iterator: Iterator<E>,
    onClose: Runnable? = null,
): Stream<E> {
    val stream: Stream<E> =
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
            false,
        )
    return if (onClose == null) stream else stream.onClose(onClose)
}

/**
 * Parses [response] with this strategy, closing [response] and rethrowing if `parse` fails. A page
 * whose parse throws never becomes a [Page], so nothing else will ever close its live response —
 * releasing it here is the close-on-parse-failure invariant both paginators must keep identical.
 */
@JvmSynthetic
internal fun <T> PaginationStrategy<T>.parseOrClose(
    response: Response,
    initialRequest: Request,
): PageInfo<T> =
    try {
        parse(response, initialRequest)
    } catch (t: Throwable) {
        // parse failed → this page never becomes a Page; release it now. A failure closing the
        // response must not mask the parse failure that is actually propagating, so attach it as
        // suppressed rather than letting it replace `t`.
        try {
            response.close()
        } catch (closeFailure: Throwable) {
            t.addSuppressed(closeFailure)
        }
        throw t
    }
