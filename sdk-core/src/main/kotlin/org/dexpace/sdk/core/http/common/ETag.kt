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
value class ETag private constructor(private val raw: String) {
    /** Returns the raw header form, e.g. `"\"foo\""`, `W/"foo"`, or `*`. */
    fun toHeaderValue(): String = raw

    override fun toString(): String = raw

    /** `true` when this is a weak validator (`W/"opaque"`). */
    val isWeak: Boolean get() = raw.startsWith("W/")

    /** `true` when this is a strong validator (`"opaque"` with no `W/` prefix and not `*`). */
    val isStrong: Boolean get() = !isWeak && raw != "*"

    /** `true` when this is the [ANY] singleton (`*`). */
    val isAny: Boolean get() = raw == "*"

    /** The opaque (unquoted) tag value, or `"*"` for [ANY]. */
    val opaque: String
        get() = if (isAny) "*" else raw.removePrefix("W/").trim('"')

    companion object {
        /**
         * Matches any entity: `If-Match: *` / `If-None-Match: *`.
         *
         * Exposed via `@JvmStatic` getter (rather than `@JvmField`) because the JVM
         * forbids storing inline-class instances as static fields — they'd auto-box at
         * the storage site and defeat the value-class optimisation. Java callers see
         * `ETag.getANY()`; Kotlin callers see `ETag.ANY`.
         */
        @JvmStatic
        val ANY: ETag = ETag("*")

        /**
         * Strong validator: `"opaque"`. The supplied [opaque] value is quote-wrapped.
         *
         * @throws IllegalArgumentException if [opaque] is empty (use [ANY] for `*`).
         */
        @JvmStatic
        fun strong(opaque: String): ETag {
            require(opaque.isNotEmpty()) { "opaque must not be empty (use ETag.ANY for `*`)" }
            return ETag("\"$opaque\"")
        }

        /**
         * Weak validator: `W/"opaque"`. Empty [opaque] (producing `W/""`) is permitted
         * — it is technically valid per RFC 7232 §2.3.
         */
        @JvmStatic
        fun weak(opaque: String): ETag {
            return ETag("W/\"$opaque\"")
        }

        /**
         * Parses a raw header form. Returns [ANY] for `*`, `null` for blank or missing
         * input.
         *
         * @throws IllegalArgumentException if [raw] is non-blank and does not match a
         *   recognised ETag form.
         */
        @JvmStatic
        fun parse(raw: String?): ETag? {
            val s = raw?.trim() ?: return null
            if (s.isEmpty()) return null
            if (s == "*") return ANY
            val isWeakForm = s.startsWith("W/\"") && s.endsWith("\"") && s.length >= 4
            val isStrongForm = !s.startsWith("W/") && s.startsWith("\"") && s.endsWith("\"") && s.length >= 2
            require(isWeakForm || isStrongForm) { "malformed ETag: $raw" }
            return ETag(s)
        }
    }
}
