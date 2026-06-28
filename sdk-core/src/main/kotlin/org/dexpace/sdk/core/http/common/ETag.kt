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
 */
@JvmInline
public value class ETag private constructor(private val raw: String) {
    /** Returns the raw header form, e.g. `"\"foo\""`, `W/"foo"`, or `*`. */
    public fun toHeaderValue(): String = raw

    override fun toString(): String = raw

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
         * Exposed via `@JvmStatic` getter (rather than `@JvmField`) because the JVM
         * forbids storing inline-class instances as static fields — they'd auto-box at
         * the storage site and defeat the value-class optimisation. Java callers see
         * `ETag.getANY()`; Kotlin callers see `ETag.ANY`.
         */
        @JvmStatic
        public val ANY: ETag = ETag("*")

        /**
         * Strong validator: `"opaque"`. The supplied [opaque] value is quote-wrapped.
         *
         * @throws IllegalArgumentException if [opaque] is empty (use [ANY] for `*`).
         */
        @JvmStatic
        public fun strong(opaque: String): ETag {
            require(opaque.isNotEmpty()) { "opaque must not be empty (use ETag.ANY for `*`)" }
            return ETag("\"$opaque\"")
        }

        /**
         * Weak validator: `W/"opaque"`. Empty [opaque] (producing `W/""`) is permitted
         * — it is technically valid per RFC 7232 §2.3.
         */
        @JvmStatic
        public fun weak(opaque: String): ETag = ETag("W/\"$opaque\"")

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

        // Minimum length for a weak-form ETag: `W/"x"` is 4 chars (`W/"` prefix + `"`
        // suffix + at least 1 opaque char between).
        private const val WEAK_FORM_MIN_LEN = 4
    }
}
