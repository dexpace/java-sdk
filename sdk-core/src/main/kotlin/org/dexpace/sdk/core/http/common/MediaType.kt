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
 * Represents a media type, as defined in the HTTP specification.
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
    /**
     * The full representation of a standard media type consisting of type/subtype
     */
    val fullType: String
        get() = "$type/$subtype"

    /**
     * The charset parameter if present, null otherwise
     * */
    val charset: Charset?
        get() {
            return parameters["charset"]?.let {
                try {
                    Charset.forName(it)
                } catch (_: Exception) {
                    null
                }
            }
        }

    /**
     * Checks if this media type includes the given media type.
     *
     * @param other The media type to compare against.
     * @return `true` if this media type includes the given media type, `false` otherwise.
     */
    fun includes(other: MediaType): Boolean {
        val typeMatches = this.type == "*" || this.type.equals(other.type, ignoreCase = true)
        val subtypeMatches = this.subtype == "*" || this.subtype.equals(other.subtype, ignoreCase = true)

        return typeMatches && subtypeMatches
    }

    /**
     * Returns the full representation of a standard media type consisting of type/subtype followed by all parameters, if any
     * */
    override fun toString(): String {
        val formattedParams =
            parameters.entries.joinToString(separator = ";") { (key, value) ->
                "$key=$value"
            }
        return if (formattedParams.isNotEmpty()) "$type/$subtype;$formattedParams" else "$type/$subtype"
    }

    companion object {
        /**
         * Factory method for creating a MediaType.
         */
        @JvmStatic
        @JvmOverloads
        fun of(type: String, subtype: String, parameters: Map<String, String> = emptyMap()): MediaType {
            require(type.isNotBlank()) { "Type must not be blank" }
            require(subtype.isNotBlank()) { "Subtype must not be blank" }

            if (type == "*" && subtype != "*") {
                throw IllegalArgumentException("Invalid media type format: type=$type, subtype=$subtype")
            }

            return MediaType(
                type = type.lowercase(Locale.getDefault()),
                subtype = subtype.lowercase(Locale.getDefault()),
                parameters = parameters.mapKeys { it.key.lowercase(Locale.getDefault()) }
            )
        }

        /**
         * Parses a media type string into a [MediaType] object.
         *
         * @param mediaType The media type string to parse.
         * @return The parsed [MediaType].
         * @throws IllegalArgumentException If the media type cannot be parsed.
         */
        @JvmStatic
        fun parse(mediaType: String): MediaType {
            require(mediaType.isNotBlank()) { "Media type must not be blank" }

            // Split into MIME type and optional parameters
            val parts = mediaType.split(";").map(String::trim)
            val mimeString = parts.first()
            val parametersList = parts.drop(1)

            // Parse type and subtype
            val slashIndex = mimeString.indexOf("/")
            if (slashIndex == -1 || slashIndex == 0 || slashIndex == mimeString.length - 1) {
                throw IllegalArgumentException("Invalid media type format: $mediaType")
            }
            val type = mimeString.substring(0, slashIndex).trim().lowercase(Locale.getDefault())
            val subtype = mimeString.substring(slashIndex + 1).trim().lowercase(Locale.getDefault())

            if (type == "*" && subtype != "*") {
                throw IllegalArgumentException("Invalid media type format: $mediaType")
            }

            val parametersMap =
                parametersList
                    .filter(String::isNotBlank)
                    .associate { parameter ->
                        // Split the parameter into key-value parts
                        val parts = parameter.split("=").map(String::trim)
                        val isValid = parts.size == 2 && parts.none { it.isBlank() }

                        if (!isValid) {
                            throw IllegalArgumentException("Invalid parameter format: $parameter")
                        }

                        val key = parts[0].lowercase(Locale.getDefault())
                        val value = parts[1].lowercase(Locale.getDefault())

                        key to value
                    }

            return MediaType(type, subtype, parametersMap)
        }
    }
}
