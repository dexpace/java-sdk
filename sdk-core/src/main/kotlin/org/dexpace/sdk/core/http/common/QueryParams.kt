/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

// Public API surface — not every accessor/mutator on this class is referenced within this module; SDK consumers may use any.

/**
 * Immutable, insertion-ordered, multi-valued model of a URL query string.
 *
 * Where [Headers] models HTTP header fields, [QueryParams] models the `?name=value&...`
 * portion of a URL. The two share their shape deliberately — private constructor, mutable
 * [Builder], multi-value semantics — but differ in two respects:
 *
 * 1. **Names are case-sensitive.** Unlike header field names, query-parameter names are
 *    opaque to HTTP and case-sensitive by convention (`?page=1` and `?Page=1` are distinct
 *    parameters), so no normalisation is applied.
 * 2. **Values may be empty or value-less.** `?flag` (a name with no `=`) and `?flag=` (a
 *    name with an empty value) both occur in the wild; the former is modelled as an empty
 *    string value, matching `application/x-www-form-urlencoded` parsing.
 *
 * ## External vs encoded form
 *
 * The model stores **decoded** names and values. [encode] renders the
 * `application/x-www-form-urlencoded` wire form (UTF-8 percent-encoding via the JDK's
 * [URLEncoder]); [parse] is its inverse, decoding a raw query string back into a
 * [QueryParams]. Round-tripping `parse(encode(...))` preserves names, values, and order.
 *
 * ## Thread-safety
 *
 * [QueryParams] itself is immutable and therefore freely shareable. [Builder] is **not**
 * thread-safe — confine each builder instance to a single thread or guard externally.
 */
@Suppress("unused", "TooManyFunctions")
@ConsistentCopyVisibility
public data class QueryParams private constructor(
    private val paramsMap: Map<String, List<String>>,
) {
    /**
     * Returns the first value for the given parameter name, or `null` if the name is absent.
     *
     * A value-less parameter (`?flag`) reports `""` here, which is distinct from `null`
     * (absent). Use [contains] to disambiguate "present but empty" from "absent".
     */
    public fun get(name: String): String? = paramsMap[name]?.firstOrNull()

    /**
     * Returns all values for the given parameter name in insertion order, or an empty list
     * if the name is absent. The returned list is unmodifiable.
     */
    public fun values(name: String): List<String> = paramsMap[name]?.let(Collections::unmodifiableList) ?: emptyList()

    /**
     * Returns `true` if any value is present for the given parameter name.
     */
    public fun contains(name: String): Boolean = paramsMap.containsKey(name)

    /**
     * Returns an unmodifiable snapshot of all parameter names at the time of the call, in
     * insertion order.
     */
    public fun names(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(paramsMap.keys))

    /**
     * Returns an unmodifiable snapshot of all parameter entries at the time of the call.
     *
     * Each entry's value list is itself unmodifiable, so callers cannot mutate this
     * [QueryParams] instance through the returned set, its entries
     * ([Map.Entry.setValue]), or the per-name value lists.
     */
    public fun entries(): Set<Map.Entry<String, List<String>>> {
        val snapshot = LinkedHashMap<String, List<String>>(paramsMap.size)
        paramsMap.forEach { (key, value) ->
            snapshot[key] = Collections.unmodifiableList(value)
        }
        return Collections.unmodifiableMap(snapshot).entries
    }

    /**
     * Returns `true` if no parameters are present.
     */
    public fun isEmpty(): Boolean = paramsMap.isEmpty()

    /**
     * Returns the number of distinct *values* across all names (not the number of names).
     *
     * `?a=1&a=2&b=3` reports `3`. Size is derived from the backing map rather than tracked,
     * so it always agrees with [entries].
     */
    public fun size(): Int = paramsMap.values.sumOf { it.size }

    /**
     * Renders this query string in `application/x-www-form-urlencoded` form, percent-encoding
     * names and values with UTF-8. Insertion order is preserved; repeated names are emitted
     * once per value (`a=1&a=2`). Returns an empty string when there are no parameters.
     *
     * The leading `?` is **not** included — callers splice it in when assembling a URL.
     */
    public fun encode(): String {
        if (paramsMap.isEmpty()) return ""
        val sb = StringBuilder()
        paramsMap.forEach { (name, valueList) ->
            val encodedName = URLEncoder.encode(name, UTF_8)
            valueList.forEach { value ->
                if (sb.isNotEmpty()) sb.append('&')
                sb.append(encodedName).append('=').append(URLEncoder.encode(value, UTF_8))
            }
        }
        return sb.toString()
    }

    /**
     * Returns a new [Builder] initialised with these parameters.
     */
    public fun newBuilder(): Builder = Builder(this)

    override fun toString(): String = paramsMap.toString()

    /**
     * Builder for constructing [QueryParams] instances. Mutators return `this` so calls can
     * be chained.
     */
    @Suppress("TooManyFunctions")
    public class Builder {
        private val paramsMap: MutableMap<String, MutableList<String>> = LinkedHashMap()

        /** Creates an empty builder. */
        public constructor()

        /** Creates a builder pre-filled with the parameters from [params]. */
        public constructor(params: QueryParams) : this() {
            params.paramsMap.forEach { (key, values) ->
                paramsMap[key] = values.toMutableList()
            }
        }

        /**
         * Appends a single value under [name]. Repeated calls accumulate, producing a
         * multi-valued parameter (`?a=1&a=2`). A `null` [value] is stored as the empty
         * string, modelling a value-less parameter (`?flag`).
         */
        public fun add(
            name: String,
            value: String?,
        ): Builder = apply { paramsMap.getOrPut(name) { mutableListOf() }.add(value ?: "") }

        /**
         * Appends all [values] under [name] in order. Existing values for [name] are kept.
         */
        public fun add(
            name: String,
            values: List<String>,
        ): Builder = apply { paramsMap.getOrPut(name) { mutableListOf() }.addAll(values) }

        /**
         * Replaces all values for [name] with the single [value]. A `null` [value] removes
         * the parameter entirely (matching [Headers.Builder.set]).
         */
        public fun set(
            name: String,
            value: String?,
        ): Builder =
            apply {
                if (value == null) {
                    remove(name)
                } else {
                    paramsMap[name] = mutableListOf(value)
                }
            }

        /**
         * Replaces all values for [name] with [values].
         */
        public fun set(
            name: String,
            values: List<String>,
        ): Builder = apply { paramsMap[name] = values.toMutableList() }

        /**
         * Removes all values for [name]. A no-op if the name is absent.
         */
        public fun remove(name: String): Builder = apply { paramsMap.remove(name) }

        /**
         * Merges every entry from [params] into this builder, appending values for names
         * that already exist.
         */
        public fun addAll(params: QueryParams): Builder =
            apply {
                params.entries().forEach { (key, values) ->
                    paramsMap.getOrPut(key) { mutableListOf() }.addAll(values)
                }
            }

        /** Builds an immutable [QueryParams] from the builder's current state. */
        public fun build(): QueryParams {
            // Deep, defensive copy so later builder mutations cannot reach into the built
            // instance and accessor-returned lists cannot be mutated via a cast.
            val snapshot = LinkedHashMap<String, List<String>>(paramsMap.size)
            paramsMap.forEach { (key, values) ->
                snapshot[key] = Collections.unmodifiableList(ArrayList(values))
            }
            return QueryParams(snapshot)
        }
    }

    public companion object {
        private const val UTF_8: String = "UTF-8"

        /** Returns an empty builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Returns an empty [QueryParams]. */
        @JvmStatic
        public fun empty(): QueryParams = Builder().build()

        /**
         * Parses a raw query string (the part after `?`, without the leading `?`) into a
         * [QueryParams], decoding names and values with UTF-8 per
         * `application/x-www-form-urlencoded`.
         *
         * - A `null` or blank input yields an empty [QueryParams].
         * - A leading `?` is tolerated and stripped.
         * - `a=1&a=2` becomes a multi-valued `a`; `flag` (no `=`) becomes `flag` → `""`;
         *   `flag=` becomes `flag` → `""`.
         * - Empty segments (a stray `&`) are skipped.
         * - Malformed percent-encoding falls back to the raw text rather than throwing, so a
         *   legacy URL a transport already accepted cannot break parsing.
         */
        @JvmStatic
        public fun parse(query: String?): QueryParams {
            if (query.isNullOrEmpty()) return empty()
            val raw = if (query.startsWith("?")) query.substring(1) else query
            if (raw.isEmpty()) return empty()
            val builder = Builder()
            for (segment in raw.split('&')) {
                if (segment.isEmpty()) continue
                val eq = segment.indexOf('=')
                if (eq < 0) {
                    builder.add(decodeOrRaw(segment), "")
                } else {
                    val name = decodeOrRaw(segment.substring(0, eq))
                    val value = decodeOrRaw(segment.substring(eq + 1))
                    builder.add(name, value)
                }
            }
            return builder.build()
        }

        private fun decodeOrRaw(raw: String): String =
            try {
                URLDecoder.decode(raw, UTF_8)
            } catch (ignored: IllegalArgumentException) {
                // Malformed percent-encoding — keep the raw text so an unencoded ASCII
                // identifier (the common case) still round-trips and equality with a
                // caller-supplied name still holds.
                raw
            }
    }
}
