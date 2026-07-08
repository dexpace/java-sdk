/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

/**
 * Typed value for the HTTP `Range` request header (RFC 7233).
 *
 * Produced via the [bytes], [suffix], or [from] factories, all of which perform
 * construction-time validation. Multi-range values (e.g. `bytes=0-100,200-300`) are
 * intentionally **not** supported by this type — call [parse] with a single range or
 * compose multiple `Range` headers at the [Headers.Builder] level.
 *
 * Round-trips with [toHeaderValue] / [parse]: [parse] stores the accepted value verbatim, so
 * its exact casing survives a parse/format cycle (`BYTES=0-1023` is preserved as written). The
 * value must begin with the `bytes=` unit token — matched case-insensitively, but with no leading
 * whitespace — so a leading space before `bytes=` is rejected rather than preserved.
 *
 * A plain immutable final class (not a `value class`): the factories are header helpers
 * where the boxing an inline class would avoid is irrelevant, and keeping it a plain class
 * gives every factory a clean, unmangled JVM name so the range surface is reachable from Java.
 */
public class HttpRange private constructor(private val raw: String) {
    /** Header value (e.g. `"bytes=0-1023"`). */
    public fun toHeaderValue(): String = raw

    override fun toString(): String = raw

    /** Value equality over the raw header form. */
    override fun equals(other: Any?): Boolean = this === other || (other is HttpRange && other.raw == raw)

    override fun hashCode(): Int = raw.hashCode()

    public companion object {
        /**
         * Range `bytes=offset-(offset+length-1)`.
         *
         * @throws IllegalArgumentException if [offset] < 0 or [length] <= 0.
         * @throws ArithmeticException if `offset + length - 1` overflows `Long`.
         */
        @JvmStatic
        public fun bytes(
            offset: Long,
            length: Long,
        ): HttpRange {
            require(offset >= 0) { "offset must be non-negative (got $offset)" }
            require(length > 0) { "length must be positive (got $length)" }
            // Math.addExact throws ArithmeticException on overflow; matches the spec
            // requirement of fail-fast construction-time validation.
            val end = Math.addExact(offset, length - 1)
            return HttpRange("bytes=$offset-$end")
        }

        /**
         * Suffix range `bytes=-length` (last [length] bytes of the resource).
         *
         * @throws IllegalArgumentException if [length] <= 0.
         */
        @JvmStatic
        public fun suffix(length: Long): HttpRange {
            require(length > 0) { "suffix length must be positive (got $length)" }
            return HttpRange("bytes=-$length")
        }

        /**
         * Open-ended range `bytes=offset-` (from [offset] to the end of the resource).
         *
         * @throws IllegalArgumentException if [offset] < 0.
         */
        @JvmStatic
        public fun from(offset: Long): HttpRange {
            require(offset >= 0) { "offset must be non-negative (got $offset)" }
            return HttpRange("bytes=$offset-")
        }

        /**
         * Parses a `Range` header value into an [HttpRange], storing it verbatim so its casing
         * round-trips through [toHeaderValue] (see the class KDoc for the accepted-form contract).
         *
         * @throws IllegalArgumentException if the value does not begin with the `bytes=` unit
         *   token or contains a comma (multi-range).
         */
        @JvmStatic
        public fun parse(raw: String): HttpRange {
            require(raw.startsWith("bytes=", ignoreCase = true)) {
                "unsupported range unit (only 'bytes' supported): $raw"
            }
            require(!raw.contains(',')) { "multi-range not supported: $raw" }
            return HttpRange(raw)
        }
    }
}
