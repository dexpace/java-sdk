/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import java.time.Duration
import java.util.Collections

/**
 * A single Server-Sent Event parsed from a stream, per the WHATWG SSE spec
 * (https://html.spec.whatwg.org/multipage/server-sent-events.html).
 *
 * The [data] field is a list because the spec allows multiple `data:` lines per event;
 * the application layer typically joins them with `\n`, but exposing the lines verbatim
 * lets callers reconstruct, count, or transform them as needed.
 *
 * [comment] holds the most recent comment line (lines starting with `:`) seen in this
 * event block. The spec does not require comments to be exposed, but doing so lets
 * callers detect server-side keep-alives.
 *
 * [retry] is the reconnect interval the server advised. Reconnect logic itself is the
 * caller's concern — this SDK only surfaces the value.
 *
 * The type keeps value semantics ([equals]/[hashCode]/[toString]/[copy]/component functions),
 * but is hand-written rather than a `data class` so that [data] can be defensively copied at
 * construction — a `data class` cannot transform a primary-constructor property.
 *
 * @property id Last `id:` field seen in this event block, or `null` if absent. Per WHATWG
 *   SSE §9.2.6, an `id:` field whose value contains a U+0000 NULL byte is ignored entirely,
 *   so it never appears here and never updates the last event ID.
 * @property event Last `event:` field seen in this event block, or `null` if absent.
 * @property data All `data:` field values seen in this event block, in order. This is an
 *   unmodifiable defensive copy taken at construction: the caller's list (and any later
 *   mutation of it) cannot reach inside the event, and the exposed list cannot be downcast to
 *   [MutableList] and altered. It participates in value semantics like any other property.
 * @property comment Most recent comment line, or `null` if none was seen.
 * @property retry Reconnect interval advised by the server, or `null` if not advised.
 */
public class ServerSentEvent
    @JvmOverloads
    constructor(
        public val id: String? = null,
        public val event: String? = null,
        data: List<String> = emptyList(),
        public val comment: String? = null,
        public val retry: Duration? = null,
    ) {
        public val data: List<String> = Collections.unmodifiableList(ArrayList(data))

        /**
         * True if this event has no meaningful payload. Useful when filtering out
         * empty dispatches that might slip through if a producer ever sends one.
         *
         * **Comment-only events count as non-empty** by this definition — `comment != null`
         * flips the flag. The WHATWG spec is silent on whether to expose pure `:keep-alive`
         * dispatches; this SDK exposes them (and the reader emits them), and `isEmpty`
         * reflects that choice so RFC keep-alives appear non-empty to filtering code.
         */
        public val isEmpty: Boolean
            get() = id == null && event == null && data.isEmpty() && comment == null && retry == null

        public operator fun component1(): String? = id

        public operator fun component2(): String? = event

        public operator fun component3(): List<String> = data

        public operator fun component4(): String? = comment

        public operator fun component5(): Duration? = retry

        /**
         * Returns a copy of this event with the supplied fields replaced. Like the constructor,
         * the [data] argument is defensively copied into a fresh unmodifiable list.
         */
        @JvmOverloads
        public fun copy(
            id: String? = this.id,
            event: String? = this.event,
            data: List<String> = this.data,
            comment: String? = this.comment,
            retry: Duration? = this.retry,
        ): ServerSentEvent = ServerSentEvent(id, event, data, comment, retry)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ServerSentEvent) return false
            return id == other.id &&
                event == other.event &&
                data == other.data &&
                comment == other.comment &&
                retry == other.retry
        }

        override fun hashCode(): Int {
            var result = id?.hashCode() ?: 0
            result = HASH_MULTIPLIER * result + (event?.hashCode() ?: 0)
            result = HASH_MULTIPLIER * result + data.hashCode()
            result = HASH_MULTIPLIER * result + (comment?.hashCode() ?: 0)
            result = HASH_MULTIPLIER * result + (retry?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "ServerSentEvent(id=$id, event=$event, data=$data, comment=$comment, retry=$retry)"

        private companion object {
            private const val HASH_MULTIPLIER = 31
        }
    }
