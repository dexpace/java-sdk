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
 *     println(event.data.joinToString("\n"))
 * }
 * ```
 */
fun BufferedSource.readServerSentEvents(): Sequence<ServerSentEvent> {
    val reader = ServerSentEventReader(this)
    return generateSequence { reader.next() }
}
