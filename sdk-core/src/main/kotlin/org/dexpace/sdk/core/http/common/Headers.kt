/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.util.Locale

// Public API surface — not every accessor/mutator on this class is referenced within this module; SDK consumers may use any.

/**
 * Represents a collection of HTTP headers.
 *
 * Header names are normalised to lower case (via [Locale.US]) at storage time so that
 * lookup, mutation, and equality are all case-insensitive. Insertion order of distinct
 * names is preserved by the backing [LinkedHashMap]. Both a [String]-based API and an
 * [HttpHeaderName]-typed API are exposed — they are equivalent and interoperate freely:
 * a header added under one form is visible to the other.
 *
 * Multi-value semantics: [add] appends to the list of values for a name; [set] replaces
 * the entire list. This matches the HTTP requirement that some headers (`Set-Cookie`,
 * `WWW-Authenticate`, `Via`) may legitimately repeat.
 *
 * ## Thread-safety
 *
 * [Headers] itself is immutable and therefore freely shareable. [Builder] is **not**
 * thread-safe — confine each builder instance to a single thread or guard externally.
 */
@Suppress("unused")
@ConsistentCopyVisibility
public data class Headers private constructor(
    private val headersMap: Map<String, List<String>>,
) {
    /**
     * Returns the first header value for the given name, or null if none.
     *
     * @param name the header name (case-insensitive)
     * @return the first header value, or null if not found
     */
    public fun get(name: String): String? = headersMap[sanitizeName(name)]?.firstOrNull()

    /**
     * Returns the first header value for the given typed name, or null if none.
     */
    public fun get(name: HttpHeaderName): String? = headersMap[name.caseInsensitiveName]?.firstOrNull()

    /**
     * Returns all header values for the given name.
     *
     * @param name the header name (case-insensitive)
     * @return an unmodifiable list of header values, or an empty list if none
     */
    public fun values(name: String): List<String> = headersMap[sanitizeName(name)] ?: emptyList()

    /**
     * Returns all header values for the given typed name.
     */
    public fun values(name: HttpHeaderName): List<String> = headersMap[name.caseInsensitiveName] ?: emptyList()

    /**
     * Returns true if any value is present for the given name.
     */
    public fun contains(name: String): Boolean = headersMap.containsKey(sanitizeName(name))

    /**
     * Returns true if any value is present for the given typed name.
     */
    public fun contains(name: HttpHeaderName): Boolean = headersMap.containsKey(name.caseInsensitiveName)

    /**
     * Returns an unmodifiable snapshot of all header names at the time of the call.
     *
     * Returns a defensive copy (`Set<String>`) so that callers cannot observe later
     * mutations to this [Headers] instance through the returned set, and so that they
     * cannot accidentally mutate the backing map by casting the return value.
     *
     * @return an immutable snapshot of header names
     */
    public fun names(): Set<String> = headersMap.keys.toSet()

    /**
     * Returns an unmodifiable list of all header entries.
     *
     * @return an unmodifiable list of header entries as [Map.Entry]
     */
    public fun entries(): Set<Map.Entry<String, List<String>>> = headersMap.entries

    /**
     * Returns a new [Builder] initialized with the existing headers.
     *
     * @return a new [Builder]
     */
    public fun newBuilder(): Builder = Builder(this)

    override fun toString(): String = headersMap.toString()

    /**
     * Builder for constructing [Headers] instances.
     */
    public class Builder {
        private val headersMap: MutableMap<String, MutableList<String>> = LinkedHashMap()

        /**
         * Creates a new builder
         */
        public constructor()

        /**
         * Creates a new builder initialized with the headers from [headers].
         *
         * @param headers the headers to initialize from
         */
        public constructor(headers: Headers) : this() {
            headers.headersMap.forEach { (key, values) ->
                headersMap[key] = values.toMutableList()
            }
        }

        /**
         * Adds a header with the specified name and value.
         * Multiple headers with the same name are allowed.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public fun add(
            name: String,
            value: String,
        ): Builder = apply { add(name, listOf(value)) }

        /**
         * Adds all header values for the specified name.
         *
         * @param name the header name
         * @param values the list of header values
         * @return this builder
         */
        public fun add(
            name: String,
            values: List<String>,
        ): Builder =
            apply {
                headersMap.computeIfAbsent(sanitizeName(name)) { mutableListOf() }.addAll(values)
            }

        /**
         * Adds a header with the specified typed name and value.
         */
        public fun add(
            name: HttpHeaderName,
            value: String,
        ): Builder = apply { add(name, listOf(value)) }

        /**
         * Adds all header values for the specified typed name.
         */
        public fun add(
            name: HttpHeaderName,
            values: List<String>,
        ): Builder =
            apply {
                headersMap.computeIfAbsent(name.caseInsensitiveName) { mutableListOf() }.addAll(values)
            }

        /**
         * Sets the header with the specified name to the single value provided.
         * If headers with this name already exist, they are removed. Passing a `null`
         * [value] removes the header entirely (Azure / OkHttp semantics).
         *
         * @param name the header name
         * @param value the header value, or `null` to remove
         * @return this builder
         */
        public fun set(
            name: String,
            value: String?,
        ): Builder =
            apply {
                if (value == null) {
                    remove(name)
                } else {
                    set(name, listOf(value))
                }
            }

        /**
         * Sets the header with the specified name to the values list provided.
         * If headers with this name already exist, they are removed.
         *
         * @param name the header name
         * @param values the header values
         * @return this builder
         */
        public fun set(
            name: String,
            values: List<String>,
        ): Builder =
            apply {
                headersMap[sanitizeName(name)] = values.toMutableList()
            }

        /**
         * Sets the header with the specified typed name to the single value provided.
         * Passing `null` removes the header.
         */
        public fun set(
            name: HttpHeaderName,
            value: String?,
        ): Builder =
            apply {
                if (value == null) {
                    remove(name)
                } else {
                    set(name, listOf(value))
                }
            }

        /**
         * Sets the header with the specified typed name to the values list provided.
         */
        public fun set(
            name: HttpHeaderName,
            values: List<String>,
        ): Builder =
            apply {
                headersMap[name.caseInsensitiveName] = values.toMutableList()
            }

        /**
         * Removes any header with the specified name.
         *
         * @param name the header name
         * @return this builder
         */
        public fun remove(name: String): Builder =
            apply {
                headersMap.remove(sanitizeName(name))
            }

        /**
         * Removes any header with the specified typed name.
         */
        public fun remove(name: HttpHeaderName): Builder =
            apply {
                headersMap.remove(name.caseInsensitiveName)
            }

        /**
         * Merges all entries from [headers] into this builder, appending values for any
         * names that already exist.
         */
        public fun addAll(headers: Headers): Builder =
            apply {
                headers.entries().forEach { (key, values) ->
                    headersMap.computeIfAbsent(key) { mutableListOf() }.addAll(values)
                }
            }

        /**
         * Builds an immutable [Headers] instance.
         *
         * @return the built [Headers]
         */
        public fun build(): Headers = Headers(LinkedHashMap(headersMap))
    }

    public companion object {
        /** Returns an empty builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Normalises a header name to its canonical (lower-case, trimmed) storage key.
         * `Locale.US` is used deliberately — HTTP header names are ASCII-only per RFC 7230,
         * so locale-sensitive folding (Turkish `i`, etc.) would be incorrect here.
         */
        private fun sanitizeName(value: String): String = value.lowercase(Locale.US).trim()
    }
}
