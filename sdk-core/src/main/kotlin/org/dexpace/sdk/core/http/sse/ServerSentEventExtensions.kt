@file:JvmName("ServerSentEvents")

package org.dexpace.sdk.core.http.sse

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
