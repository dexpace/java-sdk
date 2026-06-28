/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import java.io.IOException
import java.util.Spliterator
import java.util.Spliterators
import java.util.stream.Stream
import java.util.stream.StreamSupport

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
        private var iterated: Boolean = false

        /**
         * Returns the single auto-closing iterator over the underlying walk. The page returned by
         * `next()` stays open until the next `next()`, exhaustion, or [close].
         *
         * @throws IllegalStateException if called more than once on this view.
         */
        override fun iterator(): Iterator<Page<T>> {
            check(!iterated) { "CloseablePages is single-use; call byPage() again to restart pagination." }
            iterated = true
            return object : Iterator<Page<T>> {
                override fun hasNext(): Boolean {
                    val has = source.hasNext()
                    if (!has) closeCurrent() // close the last page when iteration is exhausted
                    return has
                }

                override fun next(): Page<T> {
                    closeCurrent() // close the previous page as we advance past it
                    return source.next().also { current = it }
                }
            }
        }

        /** Sequential, ORDERED, unknown-size stream over the same auto-closing iteration. */
        public fun stream(): Stream<Page<T>> =
            StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED),
                false,
            )

        /**
         * Closes the page currently held open (the one being inspected). Safe to call repeatedly.
         *
         * @throws IOException If the held page's [Page.close] (and thus its [Page.response] close)
         *   fails.
         */
        @Throws(IOException::class)
        override fun close() {
            closeCurrent()
        }

        private fun closeCurrent() {
            current?.close()
            current = null // null after close so a page is never double-closed
        }
    }
