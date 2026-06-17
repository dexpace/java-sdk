/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import java.time.Duration

/**
 * Per-phase timeout overlay carried by [CallOptions].
 *
 * Each phase ([connect], [write], [read], [call]) is an *overlay*: a non-`null` value
 * overrides the inherited client-level timeout for that phase, while `null` means
 * "inherit — leave the client default in place". This is deliberately distinct from the
 * absolute, fully-resolved timeouts a transport ultimately applies; a [CallTimeout] only
 * expresses the per-call *deltas* a caller wants layered on top of the client configuration.
 *
 * The four phases mirror the standard HTTP client timeout surface:
 *  - [connect] — establishing the TCP / TLS connection.
 *  - [write] — streaming the request body to the server.
 *  - [read] — waiting for / streaming the response.
 *  - [call] — an end-to-end ceiling spanning the whole exchange (retries excluded).
 *
 * ## Merge semantics
 *
 * [applyDefaults] performs per-field null-coalescing: a phase set on the receiver wins;
 * otherwise the same phase from the supplied defaults is used. Merging is associative in the
 * usual overlay sense — `a.applyDefaults(b).applyDefaults(c)` resolves each phase to the
 * first non-`null` value in `a, b, c`.
 *
 * ## Thread-safety
 *
 * Immutable; instances are freely shareable. [Builder] is single-thread / externally guarded.
 *
 * @property connect Connect-phase overlay, or `null` to inherit.
 * @property write Write-phase overlay, or `null` to inherit.
 * @property read Read-phase overlay, or `null` to inherit.
 * @property call Whole-call overlay, or `null` to inherit.
 */
@ConsistentCopyVisibility
public data class CallTimeout private constructor(
    val connect: Duration?,
    val write: Duration?,
    val read: Duration?,
    val call: Duration?,
) {
    /**
     * Returns `true` when no phase is overridden, i.e. this overlay contributes nothing and
     * the inherited client timeouts apply unchanged. Equivalent to `this == NONE`.
     */
    public fun isEmpty(): Boolean = connect == null && write == null && read == null && call == null

    /**
     * Overlays this timeout onto [defaults]: for each phase, this overlay's value wins when
     * set, otherwise the corresponding value from [defaults] is taken. The result therefore
     * resolves each phase to the first non-`null` of `(this, defaults)`.
     *
     * @param defaults The lower-priority overlay supplying values for phases this one leaves unset.
     * @return A merged overlay; returns `this` unchanged when the merge produces no difference.
     */
    public fun applyDefaults(defaults: CallTimeout): CallTimeout {
        val merged =
            CallTimeout(
                connect = connect ?: defaults.connect,
                write = write ?: defaults.write,
                read = read ?: defaults.read,
                call = call ?: defaults.call,
            )
        return if (merged == this) this else merged
    }

    /**
     * Returns a new [Builder] pre-filled with this overlay's phases.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Mutable builder for [CallTimeout]. Implements [org.dexpace.sdk.core.generics.Builder] so
     * it composes with builder-folding helpers.
     */
    public class Builder : org.dexpace.sdk.core.generics.Builder<CallTimeout> {
        private var connect: Duration? = null
        private var write: Duration? = null
        private var read: Duration? = null
        private var call: Duration? = null

        /** Creates an empty builder (every phase inherits). */
        public constructor()

        /** Creates a builder initialized from [timeout]. */
        public constructor(timeout: CallTimeout) {
            this.connect = timeout.connect
            this.write = timeout.write
            this.read = timeout.read
            this.call = timeout.call
        }

        /** Overrides the connect-phase timeout. Pass `null` to inherit. */
        public fun connect(connect: Duration?): Builder = apply { this.connect = connect }

        /** Overrides the write-phase timeout. Pass `null` to inherit. */
        public fun write(write: Duration?): Builder = apply { this.write = write }

        /** Overrides the read-phase timeout. Pass `null` to inherit. */
        public fun read(read: Duration?): Builder = apply { this.read = read }

        /** Overrides the whole-call timeout. Pass `null` to inherit. */
        public fun call(call: Duration?): Builder = apply { this.call = call }

        /**
         * Builds the [CallTimeout].
         *
         * @throws IllegalArgumentException If any set phase is negative.
         */
        override fun build(): CallTimeout {
            requireNonNegative("connect", connect)
            requireNonNegative("write", write)
            requireNonNegative("read", read)
            requireNonNegative("call", call)
            return CallTimeout(connect = connect, write = write, read = read, call = call)
        }

        private fun requireNonNegative(
            phase: String,
            value: Duration?,
        ) {
            require(value == null || !value.isNegative) { "$phase timeout must not be negative" }
        }
    }

    public companion object {
        /** The empty overlay: every phase inherits. */
        @JvmField
        public val NONE: CallTimeout = CallTimeout(connect = null, write = null, read = null, call = null)

        /** Returns a fresh empty builder. Java-friendly `CallTimeout.builder()` entry point. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Convenience for a [call]-only overlay (the most common per-call knob). Equivalent to
         * `builder().call(duration).build()`.
         */
        @JvmStatic
        public fun ofCall(call: Duration): CallTimeout = Builder().call(call).build()
    }
}
