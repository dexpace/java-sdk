/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.BufferedSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [AutoCloseable] [Iterable] of [ServerSentEvent] whose lifecycle is bound to the
 * underlying HTTP response.
 *
 * `SseStream` closes the [resource] it was opened over — typically the
 * [org.dexpace.sdk.core.http.response.Response] (or its body) — whenever the stream is
 * [closed][close], whether that close is explicit, via a `use {}` / try-with-resources block,
 * implicit when iteration runs to completion, or implicit when the backing reader fails
 * mid-stream. This mirrors the close-on-partial-consume invariant
 * [org.dexpace.sdk.core.pagination.PagedIterable] enforces: a consumer that pulls only the
 * first few events and walks away never strands the response body or its pooled connection.
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
 * - When the backing reader **fails** mid-stream the error propagates to the caller, but the
 *   response is released first, so even a consumer iterating without `use {}` does not strand
 *   the connection. A failure to release is attached to the reader error as a suppressed
 *   throwable.
 *
 * `close()` is idempotent and propagates to [resource] exactly once. The underlying response
 * `close()` is itself expected to be idempotent, so a redundant [close] here is harmless. A
 * release failure during **automatic** cleanup (clean end-of-stream) is logged at `WARN` and
 * otherwise swallowed — the events were already delivered, so it must not turn a successful read
 * into a thrown result; a release failure during an **explicit** [close] is propagated to the
 * caller.
 *
 * **Threading**: not thread-safe for iteration — drive a single iterator from one thread, as
 * with the backing reader. [close] is safe to call from another thread (e.g. to cancel a
 * long-lived stream); it flips an atomic guard, so a concurrent [close] races cleanly with
 * iteration. A thread parked **between** pulls observes the closed state and ends cleanly on
 * its next pull; a thread blocked **inside** an in-flight read when [close] tears the resource
 * down typically sees that read fail, so the cancellation surfaces to the iterating thread as
 * an [java.io.IOException] rather than a clean end. Either way the resource is released once.
 *
 * @property reader The WHATWG parser driving the byte stream. Owned by this stream.
 * @property resource The response (or body) whose lifecycle this stream governs; closed once
 *   when the stream closes.
 * @property logger Structured logger used to report a close failure during automatic
 *   end-of-stream cleanup (where the failure cannot otherwise surface to the caller).
 */
public class SseStream private constructor(
    private val reader: ServerSentEventReader,
    private val resource: Closeable,
    private val logger: ClientLogger,
) : AutoCloseable, Iterable<ServerSentEvent> {
    private val closed = AtomicBoolean(false)
    private val iteratorTaken = AtomicBoolean(false)

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
     * iteration. A failure to release [resource] is propagated to the caller — an explicit
     * close is the caller asking to release, so they own the failure.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            resource.close()
        }
    }

    /**
     * Releases [resource] exactly once on an iterator-driven terminal path (clean end-of-stream,
     * a [SseEventMapper.Result.Done] sentinel from a wrapping [TypedSseStream], or a mid-stream
     * reader/mapper failure), where the caller is not the one invoking [close] and a failure to
     * release must not become the iteration's outcome. A close failure is attached to [primary] as
     * a suppressed throwable when an error is in flight; when there is no [primary] to carry it
     * (a clean terminal path) the failure is logged at `WARN` and swallowed, since the events were
     * already delivered. Mirrors the close-on-failure helper in
     * [org.dexpace.sdk.core.pipeline.ResponsePipeline].
     */
    internal fun releaseQuietly(primary: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        try {
            resource.close()
        } catch (closeError: Throwable) {
            if (primary != null) {
                primary.addSuppressed(closeError)
            } else {
                logger.atWarning()
                    .event("sse.close.failed")
                    .cause(closeError)
                    .log("Failed to release the SSE response during end-of-stream cleanup")
            }
        }
    }

    private inner class SseIterator : AbstractIterator<ServerSentEvent>() {
        override fun computeNext() {
            // An out-of-band close() (e.g. cancellation from another thread, between pulls) ends
            // iteration cleanly rather than reading from a resource that is being torn down.
            if (closed.get()) {
                done()
                return
            }
            val event =
                try {
                    reader.next()
                } catch (readerError: Throwable) {
                    // A mid-stream reader failure (e.g. a dropped connection) releases the response
                    // before propagating, so a consumer iterating without use{} never strands the
                    // connection; any release failure is attached as suppressed. AbstractIterator
                    // then transitions to FAILED. (A close() racing an in-flight read also lands
                    // here, surfacing the cancellation as that read's IOException.)
                    releaseQuietly(readerError)
                    throw readerError
                }
            if (event == null) {
                // Clean end-of-stream: release the response. The events were already delivered, so
                // a release failure must not turn a successful read into a thrown result — it is
                // logged at WARN and swallowed here (an explicit close()/use{} still surfaces one).
                done()
                releaseQuietly(primary = null)
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
        ): SseStream = SseStream(ServerSentEventReader(source), resource, ClientLogger(SseStream::class))

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
        ): SseStream = SseStream(reader, resource, ClientLogger(SseStream::class))

        /**
         * Test-only seam: opens a stream over [reader]/[resource] with an injected [logger], so a
         * test can assert on the `WARN` emitted when end-of-stream cleanup fails to release.
         */
        @JvmSynthetic
        internal fun fromReader(
            reader: ServerSentEventReader,
            resource: Closeable,
            logger: ClientLogger,
        ): SseStream = SseStream(reader, resource, logger)
    }
}
