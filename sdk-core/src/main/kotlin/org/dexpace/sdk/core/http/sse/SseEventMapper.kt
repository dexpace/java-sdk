/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

/**
 * Maps a raw [ServerSentEvent] to a typed model, or signals that the event should be skipped
 * or that the stream is finished.
 *
 * This is the per-endpoint seam between core's format-agnostic SSE plumbing and an API's
 * own conventions. A mapper receives the event's `event:` name (or `null` if the server sent
 * no `event:` field) and the joined `data:` payload, and returns:
 *
 * - a decoded value `T` — yielded to the caller;
 * - [Skip] — the event carries no model (a keep-alive comment, a bare `id:` cursor, an
 *   ignored event type); iteration silently moves on;
 * - [Done] — a sentinel event marking end of stream (e.g. OpenAI's `data: [DONE]`); iteration
 *   terminates cleanly and the underlying [SseStream] closes.
 *
 * The mapper is the place to call the [org.dexpace.sdk.core.serde.Serde] SPI — for example
 * `serde.deserializer.deserialize(data, MyDto::class.java)` — and the place to translate an
 * error-envelope event into a thrown exception. Core deliberately holds **no** sentinel or
 * error conventions; those are per-API and live entirely in the caller-supplied mapper.
 *
 * Mapping is applied **lazily, one element at a time**, as the typed iterator is pulled, so a
 * partially consumed stream only decodes the events actually taken.
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 *
 * @param T The model type events decode into.
 */
public fun interface SseEventMapper<out T> {
    /**
     * Maps one event to a [Result].
     *
     * @param eventName The event's `event:` field, or `null` if the server omitted it.
     * @param data The event's `data:` lines joined with `\n` (empty string if the event had
     *   no `data:` field). Joining matches the conventional WHATWG client-side reconstruction.
     * @return [value] to yield a decoded model, [Skip] to drop the event, or [Done] to end
     *   the stream.
     * @throws RuntimeException the mapper may throw (e.g. on a decode failure or a mapped
     *   error-envelope); the exception propagates to the consumer's pull.
     */
    public fun map(
        eventName: String?,
        data: String,
    ): Result<T>

    /**
     * The outcome of mapping a single SSE event: a decoded [value], [Skip], or [Done].
     *
     * @param T The model type.
     */
    public sealed class Result<out T> {
        /**
         * A successfully decoded model to yield to the consumer.
         *
         * @property model The decoded value.
         */
        public class Value<out T>(public val model: T) : Result<T>()

        /**
         * The event carries no model and should be silently skipped (keep-alives, bare
         * cursors, ignored event types). Iteration continues with the next event.
         */
        public object Skip : Result<Nothing>()

        /**
         * The event is a done-sentinel: iteration terminates cleanly and the backing
         * [SseStream] closes. No model is yielded for the sentinel event itself.
         */
        public object Done : Result<Nothing>()
    }

    public companion object {
        /** Wraps [model] in a [Result.Value]. */
        @JvmStatic
        public fun <T> value(model: T): Result<T> = Result.Value(model)

        /** The shared [Result.Skip] singleton, exposed for Java callers. */
        @JvmStatic
        public fun skip(): Result<Nothing> = Result.Skip

        /** The shared [Result.Done] singleton, exposed for Java callers. */
        @JvmStatic
        public fun done(): Result<Nothing> = Result.Done
    }
}
