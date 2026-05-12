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
 *   comma-separated header value (per RFC 7232).
 * - [ifModifiedSince] / [ifUnmodifiedSince] are formatted via [DateTimeRfc1123].
 *
 * Specifying both [ifModifiedSince] and [ifUnmodifiedSince] is legal per the spec
 * (although contradictory); both pass through unchanged.
 */
class RequestConditions private constructor(
    val ifMatch: List<ETag>,
    val ifNoneMatch: List<ETag>,
    val ifModifiedSince: Instant?,
    val ifUnmodifiedSince: Instant?,
) {
    /**
     * Applies the configured conditions to [builder] as the appropriate header values.
     * Returns the same [builder] for chaining.
     *
     * Idempotent: re-applying the same [RequestConditions] to the same builder yields
     * the same header set (each call uses `set`, not `add`).
     */
    fun applyTo(builder: Headers.Builder): Headers.Builder = builder.apply {
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
    fun newBuilder(): Builder = Builder(this)

    /** Builder for [RequestConditions]. */
    class Builder {
        private val ifMatch = mutableListOf<ETag>()
        private val ifNoneMatch = mutableListOf<ETag>()
        private var ifModifiedSince: Instant? = null
        private var ifUnmodifiedSince: Instant? = null

        /** Creates an empty builder. */
        constructor()

        /** Creates a builder pre-populated with the entries of [source]. */
        constructor(source: RequestConditions) : this() {
            ifMatch.addAll(source.ifMatch)
            ifNoneMatch.addAll(source.ifNoneMatch)
            ifModifiedSince = source.ifModifiedSince
            ifUnmodifiedSince = source.ifUnmodifiedSince
        }

        /** Adds an `If-Match` validator. Multiple calls accumulate (comma-separated on the wire). */
        fun ifMatch(tag: ETag): Builder = apply { ifMatch.add(tag) }

        /** Adds an `If-None-Match` validator. Multiple calls accumulate (comma-separated on the wire). */
        fun ifNoneMatch(tag: ETag): Builder = apply { ifNoneMatch.add(tag) }

        /** Sets the `If-Modified-Since` instant; subsequent calls replace prior values. */
        fun ifModifiedSince(instant: Instant): Builder = apply { this.ifModifiedSince = instant }

        /** Sets the `If-Unmodified-Since` instant; subsequent calls replace prior values. */
        fun ifUnmodifiedSince(instant: Instant): Builder = apply { this.ifUnmodifiedSince = instant }

        fun build(): RequestConditions = RequestConditions(
            ifMatch = ifMatch.toList(),
            ifNoneMatch = ifNoneMatch.toList(),
            ifModifiedSince = ifModifiedSince,
            ifUnmodifiedSince = ifUnmodifiedSince,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
