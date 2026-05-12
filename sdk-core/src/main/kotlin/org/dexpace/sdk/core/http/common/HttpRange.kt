package org.dexpace.sdk.core.http.common

/**
 * Typed value for the HTTP `Range` request header (RFC 7233).
 *
 * Produced via the [bytes], [suffix], or [from] factories, all of which perform
 * construction-time validation. Multi-range values (e.g. `bytes=0-100,200-300`) are
 * intentionally **not** supported by this type — call [parse] with a single range or
 * compose multiple `Range` headers at the [Headers.Builder] level.
 *
 * Round-trips with [toHeaderValue] / [parse]: parsing preserves the original textual
 * form, so unusual but valid whitespace and casing survive a parse/format cycle.
 */
@JvmInline
value class HttpRange private constructor(private val raw: String) {
    /** Header value (e.g. `"bytes=0-1023"`). */
    fun toHeaderValue(): String = raw

    override fun toString(): String = raw

    companion object {
        /**
         * Range `bytes=offset-(offset+length-1)`.
         *
         * @throws IllegalArgumentException if [offset] < 0 or [length] <= 0.
         * @throws ArithmeticException if `offset + length - 1` overflows `Long`.
         */
        @JvmStatic
        fun bytes(offset: Long, length: Long): HttpRange {
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
        fun suffix(length: Long): HttpRange {
            require(length > 0) { "suffix length must be positive (got $length)" }
            return HttpRange("bytes=-$length")
        }

        /**
         * Open-ended range `bytes=offset-` (from [offset] to the end of the resource).
         *
         * @throws IllegalArgumentException if [offset] < 0.
         */
        @JvmStatic
        fun from(offset: Long): HttpRange {
            require(offset >= 0) { "offset must be non-negative (got $offset)" }
            return HttpRange("bytes=$offset-")
        }

        /**
         * Parses a `Range` header value into an [HttpRange]. Round-trips with
         * [toHeaderValue]: the original textual form is preserved (including any unusual
         * whitespace) so identical headers can be emitted on the wire.
         *
         * Only the `bytes` unit is accepted; multi-range values are rejected.
         *
         * @throws IllegalArgumentException if the unit is not `bytes` or the value
         *   contains a comma (multi-range).
         */
        @JvmStatic
        fun parse(raw: String): HttpRange {
            require(raw.startsWith("bytes=", ignoreCase = true)) {
                "unsupported range unit (only 'bytes' supported): $raw"
            }
            require(!raw.contains(',')) { "multi-range not supported: $raw" }
            return HttpRange(raw)
        }
    }
}
