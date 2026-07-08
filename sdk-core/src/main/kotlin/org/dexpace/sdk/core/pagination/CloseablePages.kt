/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import java.io.IOException
import java.io.UncheckedIOException
import java.util.stream.Stream

/**
 * Auto-closing, single-use page-level view over a lazy pagination walk.
 *
 * Each [Page] exposes its live [Page.response]; this view keeps the page returned by `next()`
 * open through the loop body and closes it automatically as the consumer advances:
 *
 * - `next()` closes the previous page before returning the new one.
 * - The terminal `hasNext()` (source exhausted) closes the last page.
 * - [close] closes the page currently held open.
 *
 * ## Prefetch and the close-on-abandon guarantee
 *
 * The underlying walk is page-lazy but its `hasNext()` is *eager*: probing for the next page runs
 * the exchange that fetches it (still exactly one HTTP exchange per page yielded — `next()` returns
 * the page the preceding `hasNext()` already fetched). This view buffers that fetched-but-not-yet-
 * returned page in a field it owns, so a `hasNext()` that is never followed by `next()` — an
 * emptiness probe, a peeking-iterator wrapper, or a `break` right after `hasNext()` — cannot strand
 * a live page. [close] releases **both** the page currently held open and any such buffered page,
 * so wrapping the view in `use { }` / try-with-resources always drains every page it fetched.
 *
 * Because probing prefetches the next page while the current one is still held, up to two live
 * pages can be open at once (the one being inspected and the one just fetched); [close] releases
 * both.
 *
 * Because an early `break`/`return` leaves the current page open, consumers MUST wrap the view in
 * `use { }` (Kotlin) or try-with-resources (Java) so that path still releases the held page:
 *
 * ```kotlin
 * pagedIterable.byPage().use { pages ->
 *     for (page in pages) {
 *         // page.response is live here
 *         if (done) break // use {} closes the still-open page
 *     }
 * }
 * ```
 *
 * The view is **single-use**: [iterator] (and therefore [stream]) may be obtained only once;
 * call `byPage()` again to restart pagination.
 *
 * @param T Element type carried in each [Page].
 */
public class CloseablePages<T>
    internal constructor(
        private val source: Iterator<Page<T>>,
    ) : AutoCloseable, Iterable<Page<T>> {
        private var current: Page<T>? = null

        // A page fetched by hasNext() but not yet returned by next(). The source's hasNext() runs
        // the fetch eagerly, so we pull that page into a field we own — otherwise a hasNext() with
        // no matching next() would strand it (unreachable by close()). Held until next() promotes
        // it to `current`, or close() releases it.
        private var prefetched: Page<T>? = null
        private var iterated: Boolean = false

        /**
         * Returns the single auto-closing iterator over the underlying walk (hold-open and
         * close-on-abandon semantics are described on the class).
         *
         * The underlying walker's `hasNext()` performs the page fetch, so this iterator's
         * `hasNext()` deliberately pulls that fetched page into a field we own — the prefetch that
         * lets [close] release a page from an abandoned probe (hence the
         * `IteratorHasNextCallsNextMethod` suppression).
         *
         * @throws IllegalStateException if called more than once on this view.
         */
        @Suppress("IteratorHasNextCallsNextMethod")
        override fun iterator(): Iterator<Page<T>> {
            check(!iterated) { "CloseablePages is single-use; call byPage() again to restart pagination." }
            iterated = true
            return object : Iterator<Page<T>> {
                override fun hasNext(): Boolean {
                    if (prefetched != null) return true // already fetched by a prior probe
                    if (!source.hasNext()) {
                        closeCurrent() // close the last page when iteration is exhausted
                        return false
                    }
                    // source.hasNext() has already run the fetch; pull the page into a field we own
                    // so close() can release it even if next() is never called.
                    prefetched = source.next()
                    return true
                }

                override fun next(): Page<T> {
                    if (!hasNext()) throw NoSuchElementException()
                    closeCurrent() // close the previous page as we advance past it
                    val page = prefetched ?: throw NoSuchElementException()
                    prefetched = null // promoted to current; no longer separately held
                    current = page
                    return page
                }
            }
        }

        /**
         * Sequential, ORDERED, unknown-size stream over the same auto-closing iteration.
         *
         * The stream registers [close] as its close handler, so a short-circuiting terminal
         * (`findFirst`, `limit`, `anyMatch`) that abandons the stream without exhausting it still
         * releases the page left held open — **provided the stream is closed**. Wrap it in
         * try-with-resources (Java) / `use { }` (Kotlin):
         *
         * ```java
         * try (Stream<Page<X>> s = pages.stream()) { s.findFirst(); } // closes the held page
         * ```
         *
         * Equivalently, this [CloseablePages] is itself the close handle — closing it also releases
         * the held page: `byPage().use { it.stream().findFirst() }`.
         *
         * [Stream.close] declares no checked exception, so the `IOException` from [close] is
         * rethrown wrapped in an [UncheckedIOException] (as the JDK's own `Files.lines` does) rather
         * than sneaky-thrown undeclared — a Java caller can then `catch (UncheckedIOException)` at
         * the try-with-resources site instead of an uncatchable-by-type checked exception.
         */
        public fun stream(): Stream<Page<T>> =
            orderedStream(
                iterator(),
                Runnable {
                    try {
                        close()
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                },
            )

        /**
         * Closes the page currently held open (the one being inspected) **and** any page fetched by
         * a `hasNext()` probe but never returned by `next()`. Safe to call repeatedly. If both
         * closes fail, the first failure propagates with the second attached as a suppressed
         * exception, so neither page's connection is leaked.
         *
         * @throws IOException If a held page's [Page.close] (and thus its [Page.response] close)
         *   fails.
         */
        @Throws(IOException::class)
        override fun close() {
            val held = current
            val pending = prefetched
            // Null out first so a page is never double-closed if close() runs again.
            current = null
            prefetched = null

            var failure: IOException? = null
            if (held != null) {
                try {
                    held.close()
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (pending != null) {
                try {
                    pending.close()
                } catch (e: IOException) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }

        private fun closeCurrent() {
            current?.close()
            current = null // null after close so a page is never double-closed
        }
    }
