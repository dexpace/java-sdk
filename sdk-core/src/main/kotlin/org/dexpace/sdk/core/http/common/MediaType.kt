/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.nio.charset.Charset
import java.util.Locale

// Public API surface — not every accessor/factory on this class is referenced within this module; SDK consumers may use any.

/**
 * Represents a media type, as defined in the HTTP specification (RFC 7231 §3.1.1.1).
 *
 * Instances are immutable and constructed exclusively through [of] and [parse]; both
 * factories normalise the type, subtype, and parameter keys to lower case so that
 * equality (via `data class`) is case-insensitive in practice. Parameter values are
 * case-preserved — boundaries, base64 tokens, and other values must not be folded.
 *
 * Wildcards: a wildcard type is only allowed when the subtype is also a wildcard
 * (i.e. the bare accept-anything form). Half-wildcard combinations follow normal
 * validation rules.
 *
 * @property type The primary type (e.g., "application", "text").
 * @property subtype The subtype (e.g., "json", "plain").
 * @property parameters The map of parameters associated with the media type (e.g., charset).
 */
@Suppress("unused")
@ConsistentCopyVisibility
public data class MediaType private constructor(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    /** The bare type/subtype form, without any parameters. */
    val fullType: String
        get() = "$type/$subtype"

    /**
     * The `charset` parameter resolved through [Charset.forName], or `null` if the parameter
     * is absent or names an unknown charset. Unknown-charset failures are swallowed
     * deliberately so callers can fall back to a default rather than wrapping every access
     * in a try/catch.
     *
     * The lookup is case-insensitive: `UTF-8`, `utf-8`, and `Utf-8` all resolve to the same
     * [Charset]. The parameter value is lower-cased before being passed to [Charset.forName],
     * normalising the cache key. The JDK accepts any case, but lowercasing here ensures a
     * consistent canonical form for comparison and logging purposes.
     */
    val charset: Charset?
        get() =
            parameters["charset"]?.let { charsetValue ->
                try {
                    Charset.forName(charsetValue.lowercase(Locale.US))
                } catch (_: Exception) {
                    null
                }
            }

    /**
     * Checks if this media type includes [other], treating wildcards in either the type or
     * subtype position as matching anything. A wildcard-subtype receiver matches any
     * concrete subtype with the same type; the reverse is not true. Parameters are not
     * considered.
     *
     * @return `true` if this media type's pattern matches [other], `false` otherwise.
     */
    public fun includes(other: MediaType): Boolean {
        val typeMatches = type == "*" || type.equals(other.type, ignoreCase = true)
        val subtypeMatches = subtype == "*" || subtype.equals(other.subtype, ignoreCase = true)
        return typeMatches && subtypeMatches
    }

    /**
     * Returns the wire form: type/subtype followed by `;key=value` for each parameter.
     * Parameters are joined without spaces around the `;` separator.
     *
     * A parameter value that is a valid RFC 7230 token is emitted bare; any value that is
     * empty or contains a non-token character (whitespace, a `;`, a `=`, a `"`, etc.) is
     * emitted as a `quoted-string` with `\` and `"` backslash-escaped (RFC 7230 §3.2.6).
     * This guarantees `parse(toString(x)) == x` for every constructible [MediaType] — e.g.
     * a multipart `boundary` value such as `a;b` round-trips without corruption.
     */
    override fun toString(): String {
        val formattedParams =
            parameters.entries.joinToString(separator = ";") { (key, value) ->
                "$key=${formatParameterValue(value)}"
            }
        return if (formattedParams.isEmpty()) fullType else "$fullType;$formattedParams"
    }

    /**
     * Renders a parameter [value] for the wire form. Bare when it is a non-empty RFC 7230
     * token; otherwise a quoted-string with `\` and `"` escaped as quoted-pairs.
     */
    private fun formatParameterValue(value: String): String {
        if (value.isNotEmpty() && value.all(::isTokenChar)) {
            return value
        }
        return buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                if (ch == '\\' || ch == '"') {
                    append('\\')
                }
                append(ch)
            }
            append('"')
        }
    }

    /**
     * True if [ch] is an RFC 7230 §3.2.6 `tchar` — a character permitted in a bare `token`:
     * an ASCII letter, an ASCII digit, or one of the [TOKEN_SPECIALS] punctuation characters.
     */
    private fun isTokenChar(ch: Char): Boolean =
        ch in 'a'..'z' ||
            ch in 'A'..'Z' ||
            ch in '0'..'9' ||
            ch in TOKEN_SPECIALS

    public companion object {
        /** RFC 7230 §3.2.6 `tchar` punctuation (the non-alphanumeric members of the token set). */
        private const val TOKEN_SPECIALS: String = "!#$%&'*+-.^_`|~"

        /**
         * Constructs a [MediaType] from explicit parts. All inputs are lower-cased and
         * validated: [type] and [subtype] must be non-blank, and a wildcard [type] is only
         * permitted when [subtype] is also a wildcard.
         *
         * @throws IllegalArgumentException if validation fails.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            type: String,
            subtype: String,
            parameters: Map<String, String> = emptyMap(),
        ): MediaType {
            require(type.isNotBlank()) { "Type must not be blank" }
            require(subtype.isNotBlank()) { "Subtype must not be blank" }
            require(type != "*" || subtype == "*") {
                "Invalid media type format: type=$type, subtype=$subtype"
            }

            return MediaType(
                type = type.lowercase(Locale.US),
                subtype = subtype.lowercase(Locale.US),
                parameters = parameters.mapKeys { it.key.lowercase(Locale.US) },
            )
        }

        /**
         * Parses a media type string into a [MediaType].
         *
         * @param mediaType the wire-form value (e.g. `application/json;charset=utf-8`).
         * @return the parsed [MediaType].
         * @throws IllegalArgumentException if the media type cannot be parsed.
         */
        @JvmStatic
        public fun parse(mediaType: String): MediaType {
            require(mediaType.isNotBlank()) { "Media type must not be blank" }

            // Split into MIME type and optional parameters. The split must respect
            // quoted-strings: a parameter value such as `boundary="a;b"` carries a `;`
            // that is NOT a parameter separator, so a naive `split(";")` would corrupt it.
            val segments = splitRespectingQuotes(mediaType, ';').map(String::trim)
            val mimeString = segments.first()
            val parameterSegments = segments.drop(1)

            // Parse type and subtype
            val slashIndex = mimeString.indexOf('/')
            require(slashIndex > 0 && slashIndex != mimeString.length - 1) {
                "Invalid media type format: $mediaType"
            }
            val type = mimeString.substring(0, slashIndex).trim().lowercase(Locale.US)
            val subtype = mimeString.substring(slashIndex + 1).trim().lowercase(Locale.US)

            require(type != "*" || subtype == "*") {
                "Invalid media type format: $mediaType"
            }

            val parametersMap =
                parameterSegments
                    .filter(String::isNotBlank)
                    .associate { parameter ->
                        // Split on the FIRST `=` only — values may legitimately contain
                        // additional `=` characters (e.g. multipart boundaries such as
                        // `boundary=abc=def`). A naive `split("=")` would reject those.
                        val equalsIndex = parameter.indexOf('=')
                        require(equalsIndex >= 0) {
                            "Invalid parameter format: $parameter"
                        }
                        val key = parameter.substring(0, equalsIndex).trim()
                        val rawValue = parameter.substring(equalsIndex + 1).trim()
                        require(key.isNotEmpty() && rawValue.isNotEmpty()) {
                            "Invalid parameter format: $parameter"
                        }
                        // RFC 7230 §3.2.6: strip surrounding double-quotes and unescape
                        // quoted-pair sequences (`\"` → `"`, `\\` → `\`).
                        // Keys are lower-cased for case-insensitive lookup; values are preserved
                        // because boundaries, base64 tokens, etc. are case-sensitive.
                        key.lowercase(Locale.US) to unescapeQuotedValue(rawValue)
                    }

            return MediaType(type, subtype, parametersMap)
        }

        /**
         * Splits [input] on [delimiter], ignoring any delimiter that appears inside a
         * double-quoted string. A backslash escapes the next character while inside quotes,
         * so an escaped quote does not end the quoted-string. This is the inverse of the
         * quoting [formatParameterValue] applies, so `parse(toString(x)) == x` holds for
         * values that contain the delimiter (e.g. a multipart `boundary`).
         */
        private fun splitRespectingQuotes(
            input: String,
            delimiter: Char,
        ): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                when {
                    inQuotes && ch == '\\' && i + 1 < input.length -> {
                        current.append(ch).append(input[i + 1])
                        i += 2
                        continue
                    }
                    ch == '"' -> {
                        inQuotes = !inQuotes
                        current.append(ch)
                    }
                    ch == delimiter && !inQuotes -> {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                    else -> current.append(ch)
                }
                i++
            }
            result.add(current.toString())
            return result
        }

        /**
         * Strips the surrounding double-quotes from a parameter [rawValue] and unescapes its
         * quoted-pair sequences (`\"` → `"`, `\\` → `\`), per RFC 7230 §3.2.6. A value that is
         * not a `quoted-string` is returned unchanged. Inverse of [formatParameterValue].
         */
        private fun unescapeQuotedValue(rawValue: String): String {
            if (!(rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2)) {
                return rawValue
            }
            val inner = rawValue.substring(1, rawValue.length - 1)
            val sb = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                if (inner[i] == '\\' && i + 1 < inner.length) {
                    sb.append(inner[i + 1])
                    i += 2
                } else {
                    sb.append(inner[i])
                    i++
                }
            }
            return sb.toString()
        }
    }
}
