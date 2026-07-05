/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import java.time.Duration
import org.dexpace.sdk.core.generics.Builder as SdkBuilder

/**
 * Per-request overrides threaded alongside a [Request] through the pipeline and into the transport.
 *
 * A [Request] models only what goes on the wire (method, URL, headers, body). Operational knobs —
 * how long to wait, how many times to retry, opaque tags for observers — are not part of the wire
 * form, so they live here instead. Threading a `RequestOptions` through
 * `HttpPipeline.send(request, options)` lets a single slow or sensitive endpoint ask for a longer
 * timeout or a smaller retry budget without standing up a whole second transport + pipeline.
 *
 * Every field is nullable / empty by default, and the default carries the semantics
 * "**use the pipeline / transport default**":
 *  - [timeout] `null` → the transport's configured timeout applies unchanged.
 *  - [maxRetries] `null` → the retry step's configured `HttpRetryOptions.maxRetries` applies.
 *  - [tags] empty → no per-call tags. Tags are carried through the call and readable by steps
 *    (via `PipelineNext.options`); the SDK itself attaches no behaviour to them.
 *
 * [EMPTY] is the canonical "override nothing" instance and is what `send(request)` threads when no
 * options are supplied, so the no-options path behaves exactly as before.
 *
 * ## Thread-safety
 * Instances are immutable and safe to share across threads. [tags] is a read-only defensive copy
 * taken at [Builder.build] time.
 *
 * @property timeout Per-call timeout override, or `null` to keep the transport default. Applied by
 *   the transport as the per-call timeout (OkHttp: the call timeout; JDK: the per-request timeout).
 * @property tags Opaque per-call tags, keyed by string. Read-only; never `null`, may be empty.
 * @property maxRetries Per-call retry-count override, or `null` to keep the retry step's configured
 *   budget. `0` disables retries for this call.
 */
public class RequestOptions private constructor(
    public val timeout: Duration?,
    public val tags: Map<String, Any>,
    public val maxRetries: Int?,
) {
    /** Returns a new [Builder] initialized with this instance's fields. */
    public fun newBuilder(): Builder = Builder(this)

    /** Value equality over [timeout], [tags], and [maxRetries]. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequestOptions) return false
        return timeout == other.timeout &&
            tags == other.tags &&
            maxRetries == other.maxRetries
    }

    /** Hash consistent with [equals]. */
    override fun hashCode(): Int {
        var result = timeout?.hashCode() ?: 0
        result = HASH_MULTIPLIER * result + tags.hashCode()
        result = HASH_MULTIPLIER * result + (maxRetries ?: 0)
        return result
    }

    override fun toString(): String = "RequestOptions(timeout=$timeout, tags=$tags, maxRetries=$maxRetries)"

    /**
     * Mutable builder for [RequestOptions]. Implements the generic [SdkBuilder] contract so it can
     * be driven by builder-folding helpers.
     */
    public class Builder : SdkBuilder<RequestOptions> {
        private var timeout: Duration? = null
        private val tags: MutableMap<String, Any> = LinkedHashMap()
        private var maxRetries: Int? = null

        /** Creates a fresh empty builder. */
        public constructor()

        /** Creates a builder initialized with the data from [options]. */
        public constructor(options: RequestOptions) {
            this.timeout = options.timeout
            this.tags.putAll(options.tags)
            this.maxRetries = options.maxRetries
        }

        /**
         * Sets the per-call timeout override, or clears it when [timeout] is `null` (fall back to
         * the transport default).
         */
        public fun timeout(timeout: Duration?): Builder =
            apply {
                this.timeout = timeout
            }

        /** Adds or replaces the tag stored under [key]. Insertion order is preserved. */
        public fun tag(
            key: String,
            value: Any,
        ): Builder =
            apply {
                this.tags[key] = value
            }

        /**
         * Sets the per-call retry-count override, or clears it when [maxRetries] is `null` (fall
         * back to the retry step's configured budget). `0` disables retries for the call.
         */
        public fun maxRetries(maxRetries: Int?): Builder =
            apply {
                this.maxRetries = maxRetries
            }

        /** Builds an immutable [RequestOptions]; [tags] is snapshotted into a read-only copy. */
        override fun build(): RequestOptions = RequestOptions(timeout, LinkedHashMap(tags), maxRetries)
    }

    public companion object {
        private const val HASH_MULTIPLIER = 31

        /** Java-friendly entry point matching the `RequestOptions.builder()` idiom. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * The canonical "override nothing" instance. Threaded by `send(request)` when no options
         * are supplied, so the no-options path keeps the transport / pipeline defaults.
         */
        @JvmField
        public val EMPTY: RequestOptions = Builder().build()
    }
}
