/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import org.dexpace.sdk.core.generics.Builder
import java.time.Duration

// Largest delay representable as Long nanoseconds (~292 years). A larger value overflows
// Duration.toNanos() when transports translate to native millisecond/nanosecond settings, so
// the builder rejects it up front rather than letting an ArithmeticException surface later.
private val MAX_NANO_REPRESENTABLE: Duration = Duration.ofNanos(Long.MAX_VALUE)

/**
 * Immutable per-phase I/O timeout budget for a single HTTP exchange.
 *
 * Splits the time a call may spend into the four phases a transport can bound independently:
 *
 *  - [connectTimeout] — establishing the TCP connection (and TLS handshake).
 *  - [readTimeout] — waiting for response bytes once the request is sent.
 *  - [writeTimeout] — flushing request bytes to the socket.
 *  - [requestTimeout] — the end-to-end ceiling for the whole call.
 *
 * [readTimeout] and [writeTimeout] **default to [requestTimeout]** when left unset, so a caller who
 * only knows "this call must finish within N seconds" can set [requestTimeout] alone and have the
 * read/write phases inherit it. [connectTimeout] does not inherit — connection establishment is a
 * distinct concern that callers usually want to bound separately (often more tightly).
 *
 * Every phase uses [Duration.ZERO] to mean **no timeout** (wait indefinitely), matching the
 * convention of the underlying clients this translates to (OkHttp, `java.net.http`). The
 * [effectiveReadTimeout] / [effectiveWriteTimeout] accessors resolve the inheritance so transport
 * adapters can read the value a phase should actually enforce without re-implementing the fallback.
 *
 * ## Relationship to retry total-timeout
 *
 * This type is **independent of** the retry budget
 * ([RetrySettings.totalTimeout][org.dexpace.sdk.core.pipeline.step.retry.RetrySettings.totalTimeout]).
 * A [Timeout] bounds a *single* attempt's I/O phases; the retry total-timeout bounds the *sum* of
 * all attempts plus the backoff delays between them. They compose: each retry attempt is dispatched
 * with this per-phase budget, and the retry layer abandons further attempts once its own total
 * budget is exhausted. Neither value derives from the other.
 *
 * ## Translation to transports
 *
 * `sdk-core` defines the value type only; it does not enforce the durations. Each transport adapter
 * maps the resolved phases onto its native client (e.g. OkHttp's
 * `connectTimeout`/`readTimeout`/`writeTimeout`, or `java.net.http`'s `connectTimeout` plus a
 * per-request `timeout`). A phase of [Duration.ZERO] disables that native setting.
 *
 * ## Thread-safety
 *
 * Instances are immutable and safe to share across threads. Builders are **not** thread-safe;
 * confine to a single thread or guard externally.
 *
 * @property connectTimeout Ceiling for connection establishment. [Duration.ZERO] means no limit.
 * @property readTimeout Ceiling for reading the response. [Duration.ZERO] means no limit; when it
 *   was never set explicitly it falls back to [requestTimeout] via [effectiveReadTimeout].
 * @property writeTimeout Ceiling for writing the request. [Duration.ZERO] means no limit; when it
 *   was never set explicitly it falls back to [requestTimeout] via [effectiveWriteTimeout].
 * @property requestTimeout End-to-end ceiling for the whole call. [Duration.ZERO] means no limit.
 */
public class Timeout
    private constructor(
        public val connectTimeout: Duration,
        public val readTimeout: Duration,
        public val writeTimeout: Duration,
        public val requestTimeout: Duration,
        // True when readTimeout was set explicitly; false means "inherit requestTimeout".
        private val readExplicit: Boolean,
        // True when writeTimeout was set explicitly; false means "inherit requestTimeout".
        private val writeExplicit: Boolean,
    ) {
        /**
         * The read timeout a transport should actually enforce: [readTimeout] when it was set
         * explicitly, otherwise [requestTimeout]. [Duration.ZERO] means no limit.
         */
        public val effectiveReadTimeout: Duration
            get() = if (readExplicit) readTimeout else requestTimeout

        /**
         * The write timeout a transport should actually enforce: [writeTimeout] when it was set
         * explicitly, otherwise [requestTimeout]. [Duration.ZERO] means no limit.
         */
        public val effectiveWriteTimeout: Duration
            get() = if (writeExplicit) writeTimeout else requestTimeout

        /** Returns a fresh [TimeoutBuilder] preloaded with this instance's values. */
        public fun newBuilder(): TimeoutBuilder = TimeoutBuilder(this)

        /** Value equality across all four resolved phases (inheritance applied). */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Timeout) return false
            return connectTimeout == other.connectTimeout &&
                requestTimeout == other.requestTimeout &&
                effectiveReadTimeout == other.effectiveReadTimeout &&
                effectiveWriteTimeout == other.effectiveWriteTimeout
        }

        override fun hashCode(): Int {
            var result = connectTimeout.hashCode()
            result = HASH_PRIME * result + requestTimeout.hashCode()
            result = HASH_PRIME * result + effectiveReadTimeout.hashCode()
            result = HASH_PRIME * result + effectiveWriteTimeout.hashCode()
            return result
        }

        override fun toString(): String =
            "Timeout(connect=$connectTimeout, read=$effectiveReadTimeout, " +
                "write=$effectiveWriteTimeout, request=$requestTimeout)"

        /**
         * Mutable builder for [Timeout]. Implements the generic [Builder] contract so it can be
         * folded by builder-style configuration code.
         *
         * Each phase defaults to [Duration.ZERO] (no timeout). Setting [requestTimeout] alone makes
         * the read and write phases inherit it; setting [readTimeout] / [writeTimeout] explicitly
         * pins those phases regardless of [requestTimeout].
         */
        public class TimeoutBuilder : Builder<Timeout> {
            private var connectTimeout: Duration = Duration.ZERO
            private var readTimeout: Duration = Duration.ZERO
            private var writeTimeout: Duration = Duration.ZERO
            private var requestTimeout: Duration = Duration.ZERO
            private var readExplicit: Boolean = false
            private var writeExplicit: Boolean = false

            /** Creates an empty builder with every phase set to [Duration.ZERO] (no timeout). */
            public constructor()

            /** Creates a builder preloaded with the values from [timeout]. */
            public constructor(timeout: Timeout) {
                this.connectTimeout = timeout.connectTimeout
                this.readTimeout = timeout.readTimeout
                this.writeTimeout = timeout.writeTimeout
                this.requestTimeout = timeout.requestTimeout
                this.readExplicit = timeout.readExplicit
                this.writeExplicit = timeout.writeExplicit
            }

            /** Sets [Timeout.connectTimeout]. Must be non-negative. [Duration.ZERO] disables it. */
            public fun connectTimeout(connectTimeout: Duration): TimeoutBuilder =
                apply {
                    this.connectTimeout = validated(connectTimeout, "connectTimeout")
                }

            /**
             * Sets [Timeout.readTimeout] explicitly, pinning the read phase so it no longer inherits
             * [requestTimeout]. Must be non-negative. [Duration.ZERO] disables it.
             */
            public fun readTimeout(readTimeout: Duration): TimeoutBuilder =
                apply {
                    this.readTimeout = validated(readTimeout, "readTimeout")
                    this.readExplicit = true
                }

            /**
             * Sets [Timeout.writeTimeout] explicitly, pinning the write phase so it no longer
             * inherits [requestTimeout]. Must be non-negative. [Duration.ZERO] disables it.
             */
            public fun writeTimeout(writeTimeout: Duration): TimeoutBuilder =
                apply {
                    this.writeTimeout = validated(writeTimeout, "writeTimeout")
                    this.writeExplicit = true
                }

            /**
             * Sets [Timeout.requestTimeout], the end-to-end ceiling that the read and write phases
             * inherit unless pinned explicitly. Must be non-negative. [Duration.ZERO] disables it.
             */
            public fun requestTimeout(requestTimeout: Duration): TimeoutBuilder =
                apply {
                    this.requestTimeout = validated(requestTimeout, "requestTimeout")
                }

            /** Builds the immutable [Timeout] instance. */
            override fun build(): Timeout =
                Timeout(
                    connectTimeout = connectTimeout,
                    readTimeout = readTimeout,
                    writeTimeout = writeTimeout,
                    requestTimeout = requestTimeout,
                    readExplicit = readExplicit,
                    writeExplicit = writeExplicit,
                )

            private fun validated(
                value: Duration,
                name: String,
            ): Duration {
                require(!value.isNegative) { "$name must be non-negative (got $value)" }
                require(value <= MAX_NANO_REPRESENTABLE) {
                    "$name must be representable in nanoseconds (≤ ~292 years); got $value"
                }
                return value
            }
        }

        public companion object {
            private const val HASH_PRIME = 31

            /**
             * The "no timeout" instance: every phase is [Duration.ZERO], so a transport waits
             * indefinitely on every phase. This is the [Timeout] equivalent of leaving timeouts
             * unconfigured, and the value [defaults] returns.
             */
            @JvmField
            public val NONE: Timeout = TimeoutBuilder().build()

            /** Java-friendly entry point: returns a fresh builder with every phase unset. */
            @JvmStatic
            public fun builder(): TimeoutBuilder = TimeoutBuilder()

            /** Returns the [NONE] timeout — no limit on any phase. */
            @JvmStatic
            public fun defaults(): Timeout = NONE

            /**
             * Convenience factory: a [Timeout] whose [requestTimeout] is [duration] and whose read
             * and write phases inherit it. [connectTimeout] is left unset ([Duration.ZERO]).
             *
             * @throws IllegalArgumentException if [duration] is negative or unrepresentable.
             */
            @JvmStatic
            public fun ofRequest(duration: Duration): Timeout = TimeoutBuilder().requestTimeout(duration).build()
        }
    }
