/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

/**
 * A reusable, per-endpoint adapter that turns an [SseStream] of raw [ServerSentEvent] into an
 * [AutoCloseable] [Iterable] of typed models `T`, applying a caller-supplied [SseEventMapper]
 * lazily as elements are pulled.
 *
 * This is the runtime primitive a code generator would target for a streaming endpoint: given
 * the AutoCloseable SSE stream (#35) and a mapper that knows the endpoint's `event:` names,
 * done-sentinel, and error-envelope conventions, it yields decoded models on demand. It is
 * **fully usable by hand today** — construct one with any lambda mapper that calls the
 * [org.dexpace.sdk.core.serde.Serde] SPI.
 *
 * Lifecycle is inherited from the wrapped [SseStream]:
 *
 * - [close] propagates to the underlying stream, which closes the response/body. Idempotent.
 * - Running iteration to completion (mapper end-of-stream or a [SseEventMapper.Result.Done]
 *   sentinel) closes the stream automatically, so a fully consumed adapter needs no explicit
 *   `close()`.
 * - Single-pass: [iterator] may be called once (the backing [SseStream] enforces this).
 *
 * **Lazy per-element decode.** The mapper — and therefore any [org.dexpace.sdk.core.serde.Serde]
 * deserialize call inside it — runs only when the consumer pulls the next element. A partial
 * consume (`first()`, `take(n)`, `stream().findFirst()`) decodes only the events actually
 * taken, then `close()` releases the rest of the response.
 *
 * **Skip / Done.** Events the mapper returns [SseEventMapper.Result.Skip] for are dropped and
 * iteration advances to the next event. A [SseEventMapper.Result.Done] ends iteration cleanly
 * and closes the stream.
 *
 * **Errors.** A mapper that throws (decode failure, mapped error-envelope) propagates the
 * exception to the consumer's pull, but the underlying [SseStream] is released first, so even a
 * consumer iterating without `use {}` does not strand the connection. A failure to release is
 * attached to the mapper error as a suppressed throwable.
 *
 * **Threading**: not thread-safe for iteration — drive a single iterator from one thread.
 * [close] is safe from another thread (delegates to [SseStream.close]).
 *
 * @param T The model type events decode into.
 * @property events The underlying raw SSE stream. Owned by this adapter; closed when this
 *   adapter closes.
 * @property mapper The per-endpoint mapping from raw event to typed [Result].
 */
public class TypedSseStream<T>(
    private val events: SseStream,
    private val mapper: SseEventMapper<T>,
) : AutoCloseable, Iterable<T> {
    /**
     * Returns the single-pass iterator over decoded models. Delegates iteration to the wrapped
     * [SseStream] and applies [mapper] lazily per element.
     *
     * @throws IllegalStateException if the underlying [SseStream] is already closed or its
     *   iterator was already taken.
     */
    override fun iterator(): Iterator<T> = MappingIterator(events.iterator())

    /**
     * Closes the adapter and the underlying [SseStream] (and thereby the response). Idempotent.
     * A failure to release is propagated, mirroring [SseStream.close].
     */
    override fun close() {
        events.close()
    }

    private inner class MappingIterator(
        private val raw: Iterator<ServerSentEvent>,
    ) : AbstractIterator<T>() {
        override fun computeNext() {
            // Pull raw events until the mapper yields a value, signals Done, or the stream ends.
            // Skips don't surface to the caller; only taken elements are decoded (lazy decode).
            // (A reader failure surfaces from raw and has already released the stream; see
            // SseStream.) Only the mapper call needs its own release-on-failure here.
            while (raw.hasNext()) {
                val event = raw.next()
                val result =
                    try {
                        mapper.map(event.event, event.data.joinToString("\n"))
                    } catch (mapperError: Throwable) {
                        // A mapper failure (decode error, mapped error-envelope) releases the
                        // underlying stream before propagating, so a consumer iterating without
                        // use{} never strands the connection; a release failure is suppressed onto
                        // the mapper error. Delegated so close-failure handling lives in one place.
                        events.releaseQuietly(mapperError)
                        throw mapperError
                    }
                when (result) {
                    is SseEventMapper.Result.Value -> {
                        setNext(result.model)
                        return
                    }
                    SseEventMapper.Result.Skip -> continue
                    SseEventMapper.Result.Done -> {
                        // Clean end at the done-sentinel: the values before it were already
                        // delivered, so a release failure here is logged at WARN and swallowed by
                        // SseStream rather than discarding them (an explicit close()/use{} still
                        // surfaces a release failure).
                        done()
                        events.releaseQuietly(primary = null)
                        return
                    }
                }
            }
            done()
        }
    }
}
