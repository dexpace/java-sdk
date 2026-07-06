/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import org.dexpace.sdk.core.util.DateTimeRfc1123
import java.time.Instant

/**
 * Aggregated conditional-request headers (`If-Match`, `If-None-Match`, `If-Modified-Since`,
 * `If-Unmodified-Since`).
 *
 * Constructed via [builder]. Apply to a request via [applyTo], which writes the configured
 * conditions onto a [Headers.Builder]:
 * - Multiple [ETag] entries in [ifMatch] / [ifNoneMatch] are emitted as a single
 *   comma-separated header value (per RFC 7232). [ETag.ANY] (`*`) is mutually exclusive with
 *   concrete entity-tags in a list — [Builder.build] rejects a mix and collapses repeated `*`
 *   to a single `*`.
 * - [ifModifiedSince] / [ifUnmodifiedSince] are formatted via [DateTimeRfc1123].
 *
 * Specifying both [ifModifiedSince] and [ifUnmodifiedSince] is legal per the spec
 * (although contradictory); both pass through unchanged.
 */
public class RequestConditions private constructor(
    public val ifMatch: List<ETag>,
    public val ifNoneMatch: List<ETag>,
    public val ifModifiedSince: Instant?,
    public val ifUnmodifiedSince: Instant?,
) {
    /**
     * Applies the configured conditions to [builder] as the appropriate header values.
     * Returns the same [builder] for chaining.
     *
     * Idempotent: re-applying the same [RequestConditions] to the same builder yields
     * the same header set (each call uses `set`, not `add`).
     */
    public fun applyTo(builder: Headers.Builder): Headers.Builder =
        builder.apply {
            if (ifMatch.isNotEmpty()) {
                set(HttpHeaderName.IF_MATCH, ifMatch.joinToString(", ") { it.toHeaderValue() })
            }
            if (ifNoneMatch.isNotEmpty()) {
                set(HttpHeaderName.IF_NONE_MATCH, ifNoneMatch.joinToString(", ") { it.toHeaderValue() })
            }
            ifModifiedSince?.let {
                set(HttpHeaderName.IF_MODIFIED_SINCE, DateTimeRfc1123.format(it))
            }
            ifUnmodifiedSince?.let {
                set(HttpHeaderName.IF_UNMODIFIED_SINCE, DateTimeRfc1123.format(it))
            }
        }

    /**
     * Returns a new [Builder] pre-populated with this instance's state, so callers can
     * derive a modified copy without rebuilding from scratch.
     */
    public fun newBuilder(): Builder = Builder(this)

    /** Builder for [RequestConditions]. */
    public class Builder {
        private val ifMatch = mutableListOf<ETag>()
        private val ifNoneMatch = mutableListOf<ETag>()
        private var ifModifiedSince: Instant? = null
        private var ifUnmodifiedSince: Instant? = null

        /** Creates an empty builder. */
        public constructor()

        /** Creates a builder pre-populated with the entries of [source]. */
        public constructor(source: RequestConditions) : this() {
            ifMatch.addAll(source.ifMatch)
            ifNoneMatch.addAll(source.ifNoneMatch)
            ifModifiedSince = source.ifModifiedSince
            ifUnmodifiedSince = source.ifUnmodifiedSince
        }

        /** Adds an `If-Match` validator. Multiple calls accumulate (comma-separated on the wire). */
        public fun ifMatch(tag: ETag): Builder = apply { ifMatch.add(tag) }

        /**
         * Adds an `If-Match` validator from its raw RFC 7232 header form (`"opaque"`,
         * `W/"opaque"`, or `*`) — a String escape hatch equivalent to
         * `ifMatch(ETag.parse(value)!!)`. Multiple calls accumulate.
         *
         * @throws IllegalArgumentException if [value] is blank or not a recognised ETag form.
         */
        public fun ifMatch(value: String): Builder = apply { ifMatch.add(parseTag(value)) }

        /** Adds an `If-None-Match` validator. Multiple calls accumulate (comma-separated on the wire). */
        public fun ifNoneMatch(tag: ETag): Builder = apply { ifNoneMatch.add(tag) }

        /**
         * Adds an `If-None-Match` validator from its raw RFC 7232 header form (`"opaque"`,
         * `W/"opaque"`, or `*`) — a String escape hatch equivalent to
         * `ifNoneMatch(ETag.parse(value)!!)`. Multiple calls accumulate.
         *
         * @throws IllegalArgumentException if [value] is blank or not a recognised ETag form.
         */
        public fun ifNoneMatch(value: String): Builder = apply { ifNoneMatch.add(parseTag(value)) }

        /** Sets the `If-Modified-Since` instant; subsequent calls replace prior values. */
        public fun ifModifiedSince(instant: Instant): Builder = apply { this.ifModifiedSince = instant }

        /** Sets the `If-Unmodified-Since` instant; subsequent calls replace prior values. */
        public fun ifUnmodifiedSince(instant: Instant): Builder = apply { this.ifUnmodifiedSince = instant }

        /**
         * Builds the [RequestConditions].
         *
         * @throws IllegalArgumentException if [ETag.ANY] (`*`) is combined with any concrete
         *   entity-tag in the same `If-Match` or `If-None-Match` list. RFC 7232 grammar is
         *   `"*" / 1#entity-tag`, so `*` is mutually exclusive with entity-tags; repeated `*`
         *   entries collapse to a single `*`.
         */
        public fun build(): RequestConditions =
            RequestConditions(
                ifMatch = normalizeTags(ifMatch.toList()),
                ifNoneMatch = normalizeTags(ifNoneMatch.toList()),
                ifModifiedSince = ifModifiedSince,
                ifUnmodifiedSince = ifUnmodifiedSince,
            )

        /**
         * Parses a raw ETag header form, rejecting blank input. [ETag.parse] returns `null`
         * for blank/missing values, which is not a usable validator here, so a blank value
         * is an [IllegalArgumentException] rather than a silent no-op.
         */
        private fun parseTag(value: String): ETag = requireNotNull(ETag.parse(value)) { "ETag value must not be blank" }

        /**
         * Normalizes an `If-Match` / `If-None-Match` tag list against the RFC 7232
         * `"*" / 1#entity-tag` grammar: [ETag.ANY] (`*`) is mutually exclusive with concrete
         * entity-tags, so a mix of the two is rejected; a list of one or more `*` entries
         * collapses to a single `*`.
         */
        private fun normalizeTags(tags: List<ETag>): List<ETag> {
            if (tags.none { it.isAny }) return tags
            require(tags.all { it.isAny }) {
                "ETag.ANY (*) is mutually exclusive with concrete entity-tags in the same " +
                    "If-Match/If-None-Match list (RFC 7232: \"*\" / 1#entity-tag)"
            }
            return listOf(ETag.ANY)
        }
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
