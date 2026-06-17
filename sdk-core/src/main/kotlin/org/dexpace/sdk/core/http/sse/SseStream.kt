/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.io.BufferedSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An [AutoCloseable] [Iterable] of [ServerSentEvent] whose lifecycle is bound to the
 * underlying HTTP response.
 *
 * `SseStream` closes the [resource] it was opened over — typically the
 * [org.dexpace.sdk.core.http.response.Response] (or its body) — whenever the stream is
 * [closed][close], whether that close is explicit, via a `use {}` / try-with-resources block,
 * or implicit when iteration runs to completion. This mirrors the close-on-partial-consume
 * invariant [org.dexpace.sdk.core.http.paging.PagedIterable] enforces: a consumer that pulls
 * only the first few events and walks away never strands the response body or its pooled
 * connection.
 *
 * Unlike the bare [Sequence] returned by [BufferedSource.readServerSentEvents], an `SseStream`
 * **owns** the response. The [ServerSentEventReader] it drives is single-pass and stateful, so
 * the stream is single-pass too:
 *
 * - [iterator] may be called **once**. A second call throws [IllegalStateException]; this
 *   surfaces the previously documented "do not iterate twice" warning as an enforced guard.
 * - Once [close] has run, [iterator] (and any further pulls from an in-flight iterator) throw
 *   [IllegalStateException] — the is-closed guard.
 * - When the backing reader reports end-of-stream the iterator terminates cleanly **and**
 *   closes the stream, releasing the response without a separate [close] call.
 *
 * `close()` is idempotent and propagates to [resource] exactly once. The underlying response
 * `close()` is itself expected to be idempotent, so a redundant [close] here is harmless.
 *
 * **Threading**: not thread-safe for iteration — drive a single iterator from one thread, as
 * with the backing reader. [close] is safe to call from another thread (e.g. to cancel a
 * long-lived stream); it takes a lock and flips an atomic guard, so a concurrent [close]
 * races cleanly with iteration and the iterating thread observes the closed state on its next
 * pull.
 *
 * @property reader The WHATWG parser driving the byte stream. Owned by this stream.
 * @property resource The response (or body) whose lifecycle this stream governs; closed once
 *   when the stream closes.
 */
public class SseStream private constructor(
    private val reader: ServerSentEventReader,
    private val resource: Closeable,
) : AutoCloseable, Iterable<ServerSentEvent> {
    private val closed = AtomicBoolean(false)
    private val iteratorTaken = AtomicBoolean(false)
    private val closeLock = ReentrantLock()

    /**
     * Returns the single-pass iterator over the stream's events.
     *
     * Each pull advances the backing [ServerSentEventReader]. When the reader signals
     * end-of-stream the iterator terminates and the stream is [closed][close] eagerly, so a
     * fully consumed stream needs no explicit `close()`.
     *
     * @throws IllegalStateException if the stream is already closed, or if [iterator] was
     *   already called on this instance (the stream is single-pass).
     */
    override fun iterator(): Iterator<ServerSentEvent> {
        check(!closed.get()) { "SseStream is closed" }
        check(iteratorTaken.compareAndSet(false, true)) {
            "SseStream is single-pass; iterator() may only be called once"
        }
        return SseIterator()
    }

    /**
     * Closes the stream and the underlying [resource]. Idempotent: only the first call
     * propagates to [resource]; later calls are no-ops. Safe to call concurrently with
     * iteration.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeLock.withLock { resource.close() }
        }
    }

    private inner class SseIterator : AbstractIterator<ServerSentEvent>() {
        override fun computeNext() {
            // An out-of-band close() (e.g. cancellation from another thread) ends iteration
            // cleanly rather than reading from a resource that is being torn down.
            if (closed.get()) {
                done()
                return
            }
            // Reader exceptions (mid-stream connection drops) propagate to the caller; the
            // stream stays open so the caller can still close()/use{} the response on the way
            // out. AbstractIterator transitions to FAILED, matching PagedIterable's contract.
            val event = reader.next()
            if (event == null) {
                // Clean end-of-stream: release the response without a separate close() call.
                done()
                close()
                return
            }
            setNext(event)
        }
    }

    public companion object {
        /**
         * Opens an [SseStream] that parses [source] and, when closed, closes [resource].
         *
         * Typical use binds [resource] to the originating
         * [org.dexpace.sdk.core.http.response.Response] so closing the stream releases the
         * response body and its connection:
         *
         * ```
         * SseStream.from(response.body!!.source(), response).use { stream ->
         *     for (event in stream) { /* handle event */ }
         * }
         * ```
         *
         * [source] and [resource] may be the same object when the source itself owns the
         * transport handle.
         *
         * @param source The byte stream to parse as Server-Sent Events.
         * @param resource The handle to close when the stream closes (response, body, etc.).
         */
        @JvmStatic
        public fun from(
            source: BufferedSource,
            resource: Closeable,
        ): SseStream = SseStream(ServerSentEventReader(source), resource)

        /**
         * Opens an [SseStream] over a pre-built [reader], closing [resource] when the stream
         * closes. Use when the caller already holds a configured [ServerSentEventReader].
         *
         * @param reader The parser to drive. Owned by the returned stream.
         * @param resource The handle to close when the stream closes.
         */
        @JvmStatic
        public fun fromReader(
            reader: ServerSentEventReader,
            resource: Closeable,
        ): SseStream = SseStream(reader, resource)
    }
}
