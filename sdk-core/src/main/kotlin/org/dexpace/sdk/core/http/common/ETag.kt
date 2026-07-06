/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

/**
 * Typed value for the HTTP `ETag`, `If-Match`, and `If-None-Match` headers (RFC 7232).
 *
 * Three flavours:
 * - **Strong**: `"opaque"` — produced by [strong]; matches only byte-identical entities.
 * - **Weak**: `W/"opaque"` — produced by [weak]; matches semantically-equivalent entities.
 * - **Any**: `*` — the [ANY] singleton; matches any entity.
 *
 * The opaque value is exposed unquoted via [opaque]; the raw header form is exposed via
 * [toHeaderValue]. Round-trips with [parse] preserve the textual form exactly.
 *
 * A plain immutable final class (not a `value class`): the factories and accessors are
 * header helpers where the boxing an inline class would avoid is irrelevant, and keeping
 * it a plain class gives every factory and accessor a clean, unmangled JVM name so the
 * whole conditional-request surface is reachable from Java.
 */
public class ETag private constructor(private val raw: String) {
    /** Returns the raw header form, e.g. `"\"foo\""`, `W/"foo"`, or `*`. */
    public fun toHeaderValue(): String = raw

    override fun toString(): String = raw

    /** Value equality over the raw header form. */
    override fun equals(other: Any?): Boolean = this === other || (other is ETag && other.raw == raw)

    override fun hashCode(): Int = raw.hashCode()

    /** `true` when this is a weak validator (`W/"opaque"`). */
    public val isWeak: Boolean get() = raw.startsWith("W/")

    /** `true` when this is a strong validator (`"opaque"` with no `W/` prefix and not `*`). */
    public val isStrong: Boolean get() = !isWeak && raw != "*"

    /** `true` when this is the [ANY] singleton (`*`). */
    public val isAny: Boolean get() = raw == "*"

    /** The opaque (unquoted) tag value, or `"*"` for [ANY]. */
    public val opaque: String
        get() = if (isAny) "*" else raw.removePrefix("W/").trim('"')

    public companion object {
        /**
         * Matches any entity: `If-Match: *` / `If-None-Match: *`.
         *
         * Exposed as a `@JvmField` static field, so Java callers see `ETag.ANY` (a field)
         * and Kotlin callers see `ETag.ANY`.
         */
        @JvmField
        public val ANY: ETag = ETag("*")

        /**
         * Strong validator: `"opaque"`. The supplied [opaque] value is quote-wrapped.
         *
         * @throws IllegalArgumentException if [opaque] is empty (use [ANY] for `*`), or contains a
         *   character outside the RFC 7232 §2.3 `etagc` set (`%x21 / %x23-7E / obs-text`) — a
         *   literal double-quote, a control character, or `DEL`.
         */
        @JvmStatic
        public fun strong(opaque: String): ETag {
            require(opaque.isNotEmpty()) { "opaque must not be empty (use ETag.ANY for `*`)" }
            return fromOpaque(opaque, weak = false)
        }

        /**
         * Weak validator: `W/"opaque"`. Empty [opaque] (producing `W/""`) is permitted
         * — it is technically valid per RFC 7232 §2.3.
         *
         * @throws IllegalArgumentException if [opaque] contains a character outside the RFC 7232
         *   §2.3 `etagc` set (`%x21 / %x23-7E / obs-text`) — a literal double-quote, a control
         *   character, or `DEL`.
         */
        @JvmStatic
        public fun weak(opaque: String): ETag = fromOpaque(opaque, weak = true)

        /**
         * Parses a raw header form. Returns [ANY] for `*`, `null` for blank or missing
         * input.
         *
         * Tightened beyond a naive `^(W/)?".*"$` regex: unterminated forms such as `"foo`
         * (opening quote only) or `W/"foo` (weak prefix without closing quote) are
         * rejected outright. The minimum-length checks guard against `"` and `W/"` —
         * which would otherwise pass a "starts and ends with quote" test trivially.
         *
         * @throws IllegalArgumentException if [raw] is non-blank and does not match a
         *   recognised ETag form.
         */
        @JvmStatic
        public fun parse(raw: String?): ETag? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            if (trimmed == "*") return ANY
            val isWeakForm =
                trimmed.length >= WEAK_FORM_MIN_LEN &&
                    trimmed.startsWith("W/\"") &&
                    trimmed.endsWith("\"")
            // A strong-form value must open with `"`, which a `W/`-prefixed weak form never
            // does, so no explicit weak-prefix exclusion is needed here.
            val isStrongForm =
                trimmed.length >= 2 &&
                    trimmed.startsWith("\"") && trimmed.endsWith("\"")
            require(isWeakForm || isStrongForm) { "malformed ETag: $raw" }
            return ETag(trimmed)
        }

        /**
         * Shared construction path for [strong] and [weak]: validates that [opaque] contains only
         * RFC 7232 §2.3 `etagc` characters, then quote-wraps it (prefixed with `W/` when [weak]).
         * Rejecting the illegal characters here keeps a raw double-quote, control character, or
         * `DEL` from breaking out of the quoted entity-tag on the wire.
         */
        private fun fromOpaque(
            opaque: String,
            weak: Boolean,
        ): ETag {
            require(opaque.all(::isEtagChar)) {
                "opaque must contain only RFC 7232 etagc characters " +
                    "(no double-quote, control characters, or DEL): \"$opaque\""
            }
            val quoted = "\"$opaque\""
            return ETag(if (weak) "W/$quoted" else quoted)
        }

        /**
         * True when [ch] is an RFC 7232 §2.3 `etagc` character: `%x21 / %x23-7E / obs-text
         * (%x80-FF)`. Excludes the double-quote (`%x22`), the C0 controls and space (below
         * `%x21`), and `DEL` (`%x7F`).
         */
        private fun isEtagChar(ch: Char): Boolean {
            val code = ch.code
            return (code in ETAGC_PRINTABLE_LOW..ETAGC_PRINTABLE_HIGH && code != DOUBLE_QUOTE) ||
                code in OBS_TEXT_LOW..OBS_TEXT_HIGH
        }

        // Minimum length for a weak-form ETag: `W/"x"` is 4 chars (`W/"` prefix + `"`
        // suffix + at least 1 opaque char between).
        private const val WEAK_FORM_MIN_LEN = 4

        /** First printable ASCII byte allowed in RFC 7232 §2.3 `etagc` (`!`). */
        private const val ETAGC_PRINTABLE_LOW: Int = 0x21

        /** The double-quote (`"`) — excluded from `etagc` so it cannot close the entity-tag. */
        private const val DOUBLE_QUOTE: Int = 0x22

        /** Last printable ASCII byte allowed in `etagc` (`~`); `DEL` (`0x7F`) sits just above it. */
        private const val ETAGC_PRINTABLE_HIGH: Int = 0x7E

        /** Lower bound of the obs-text range allowed in `etagc`. */
        private const val OBS_TEXT_LOW: Int = 0x80

        /** Upper bound of the obs-text range allowed in `etagc`. */
        private const val OBS_TEXT_HIGH: Int = 0xFF
    }
}
