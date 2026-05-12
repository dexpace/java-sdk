/*
 * Copyright (C) 2024 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dexpace.sdk.core.http.common

import java.nio.charset.Charset
import java.util.Locale

/**
 * Represents a media type, as defined in the HTTP specification (RFC 7231 §3.1.1.1).
 *
 * Instances are immutable and constructed exclusively through [of] and [parse]; both
 * factories normalise the type, subtype, and parameter keys to lower case so that
 * equality (via `data class`) is case-insensitive in practice. Parameter values are
 * also lower-cased — callers that need case-preserving values should attach them
 * out-of-band.
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
data class MediaType private constructor(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String> = emptyMap()
) {
    /** The bare type/subtype form, without any parameters. */
    val fullType: String
        get() = "$type/$subtype"

    /**
     * The `charset` parameter resolved through [Charset.forName], or `null` if the parameter
     * is absent or names an unknown charset. Unknown-charset failures are swallowed
     * deliberately so callers can fall back to a default rather than wrapping every access
     * in a try/catch.
     */
    val charset: Charset?
        get() = parameters["charset"]?.let {
            try {
                Charset.forName(it)
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
    fun includes(other: MediaType): Boolean {
        val typeMatches = type == "*" || type.equals(other.type, ignoreCase = true)
        val subtypeMatches = subtype == "*" || subtype.equals(other.subtype, ignoreCase = true)
        return typeMatches && subtypeMatches
    }

    /**
     * Returns the wire form: type/subtype followed by `;key=value` for each parameter.
     * Parameters are joined without spaces around the `;` separator.
     */
    override fun toString(): String {
        val formattedParams = parameters.entries.joinToString(separator = ";") { (key, value) ->
            "$key=$value"
        }
        return if (formattedParams.isEmpty()) "$type/$subtype" else "$type/$subtype;$formattedParams"
    }

    companion object {
        /**
         * Constructs a [MediaType] from explicit parts. All inputs are lower-cased and
         * validated: [type] and [subtype] must be non-blank, and a wildcard [type] is only
         * permitted when [subtype] is also a wildcard.
         *
         * @throws IllegalArgumentException if validation fails.
         */
        @JvmStatic
        @JvmOverloads
        fun of(type: String, subtype: String, parameters: Map<String, String> = emptyMap()): MediaType {
            require(type.isNotBlank()) { "Type must not be blank" }
            require(subtype.isNotBlank()) { "Subtype must not be blank" }
            require(!(type == "*" && subtype != "*")) {
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
        fun parse(mediaType: String): MediaType {
            require(mediaType.isNotBlank()) { "Media type must not be blank" }

            // Split into MIME type and optional parameters
            val segments = mediaType.split(";").map(String::trim)
            val mimeString = segments.first()
            val parameterSegments = segments.drop(1)

            // Parse type and subtype
            val slashIndex = mimeString.indexOf('/')
            if (slashIndex <= 0 || slashIndex == mimeString.length - 1) {
                throw IllegalArgumentException("Invalid media type format: $mediaType")
            }
            val type = mimeString.substring(0, slashIndex).trim().lowercase(Locale.US)
            val subtype = mimeString.substring(slashIndex + 1).trim().lowercase(Locale.US)

            if (type == "*" && subtype != "*") {
                throw IllegalArgumentException("Invalid media type format: $mediaType")
            }

            val parametersMap = parameterSegments
                .filter(String::isNotBlank)
                .associate { parameter ->
                    // Split on the FIRST `=` only — values may legitimately contain
                    // additional `=` characters (e.g. multipart boundaries such as
                    // `boundary=abc=def`). A naive `split("=")` would reject those.
                    val equalsIndex = parameter.indexOf('=')
                    if (equalsIndex < 0) {
                        throw IllegalArgumentException("Invalid parameter format: $parameter")
                    }
                    val key = parameter.substring(0, equalsIndex).trim()
                    val value = parameter.substring(equalsIndex + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) {
                        throw IllegalArgumentException("Invalid parameter format: $parameter")
                    }
                    key.lowercase(Locale.US) to value.lowercase(Locale.US)
                }

            return MediaType(type, subtype, parametersMap)
        }
    }
}
