/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("ServerSentEvents")

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.io.BufferedSource

/**
 * Returns a lazy [Sequence] of [ServerSentEvent] parsed from this source.
 *
 * The sequence terminates when the underlying [ServerSentEventReader] returns `null`
 * (stream end). Exceptions thrown while reading propagate through the sequence — the
 * consumer sees them on the offending `next()` call.
 *
 * Iteration is single-pass: a fresh [ServerSentEventReader] is created once and
 * reused across pulls, so the BOM-consumed flag (and any future per-stream state)
 * persists correctly across the whole iteration. Do not call this twice on the
 * same source — the second sequence would start mid-stream.
 *
 * Example:
 * ```
 * source.readServerSentEvents().forEach { event ->
 *     // handle event.data
 * }
 * ```
 */
public fun BufferedSource.readServerSentEvents(): Sequence<ServerSentEvent> {
    val reader = ServerSentEventReader(this)
    return generateSequence { reader.next() }
}

/**
 * Returns the SSE event stream as an [Iterable] so that Java callers can iterate with a
 * standard for-each loop without dealing with [Sequence] (which is Kotlin-first and has no
 * convenient Java for-each binding).
 *
 * Delegates to [readServerSentEvents] and wraps the result via [Sequence.asIterable]. All
 * semantics — lazy evaluation, single-pass restriction, exception propagation — are identical
 * to [readServerSentEvents].
 *
 * Java usage:
 * ```java
 * for (ServerSentEvent event : ServerSentEvents.readServerSentEventsAsIterable(source)) {
 *     // handle event.data
 * }
 * ```
 */
public fun BufferedSource.readServerSentEventsAsIterable(): Iterable<ServerSentEvent> =
    readServerSentEvents().asIterable()

/**
 * Opens an [SseStream] over this response's body, binding the stream's lifecycle to the
 * response: closing the returned stream (explicitly, via `use {}` / try-with-resources, or
 * implicitly when iteration completes) closes the response and releases its connection.
 *
 * Use this on a streaming response to consume events without stranding the body on a partial
 * consume:
 *
 * ```
 * response.sseStream().use { stream ->
 *     for (event in stream) { /* handle event */ }
 * }
 * ```
 *
 * @throws IllegalStateException if the response has no body.
 */
public fun Response.sseStream(): SseStream {
    val responseBody = checkNotNull(body) { "Response has no body to stream as Server-Sent Events" }
    return SseStream.from(responseBody.source(), this)
}

/**
 * Wraps this raw [SseStream] in a [TypedSseStream] that maps each event to a model `T` via
 * [mapper], decoding lazily on consume. The returned adapter inherits this stream's lifecycle:
 * closing it closes this stream (and the response).
 *
 * The [mapper] is the per-endpoint seam — it is where the [org.dexpace.sdk.core.serde.Serde]
 * SPI is called and where done-sentinel / error-envelope conventions live.
 *
 * @param mapper Maps a raw event to a [SseEventMapper.Result].
 */
public fun <T> SseStream.typed(mapper: SseEventMapper<T>): TypedSseStream<T> = TypedSseStream(this, mapper)
