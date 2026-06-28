/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

// Public API surface — not every accessor/mutator on this class is referenced within this module; SDK consumers may use any.

/**
 * Immutable, insertion-ordered, multi-valued model of a URL query string.
 *
 * Where [Headers] models HTTP header fields, [QueryParams] models the `?name=value&...`
 * portion of a URL. The two share their shape deliberately — private constructor, mutable
 * [Builder], multi-value semantics — but differ in three respects:
 *
 * 1. **Names are case-sensitive.** Unlike header field names, query-parameter names are
 *    opaque to HTTP and case-sensitive by convention (`?page=1` and `?Page=1` are distinct
 *    parameters), so no normalisation is applied.
 * 2. **Values may be empty or value-less.** `?flag` (a name with no `=`) and `?flag=` (a
 *    name with an empty value) both occur in the wild; the former is modelled as an empty
 *    string value.
 * 3. **Equality is order-sensitive.** Two [QueryParams] are equal only if they carry the
 *    same names *and* values in the same order — i.e. equality agrees with [encode]. (This
 *    is the one place the shape diverges from [Headers], whose case-folded names make
 *    name order non-semantic; here order is a rendered property, so it counts.)
 *
 * ## Role: building queries, not editing URLs
 *
 * [QueryParams] is an *origination* model — it builds a query string from decoded
 * names/values (e.g. projecting an operation's inputs into a request). It is **not** a
 * fidelity-preserving editor of an existing URL: [encode] re-renders every parameter in
 * canonical form, so round-tripping an arbitrary URL through [parse] then [encode] may
 * change the wire form of untouched parameters (`?flag` → `flag=`, reserved characters
 * percent-encoded). Code that must edit one parameter of an existing URL while leaving the
 * rest byte-for-byte (e.g. pagination's rebuilder) should splice the raw query instead.
 *
 * ## External vs encoded form
 *
 * The model stores **decoded** names and values. [encode] renders the query string with
 * RFC 3986 percent-encoding (space → `%20`, literal `+` → `%2B`) via [PercentEncoding];
 * [parse] is its inverse. Round-tripping `parse(encode(...))` preserves names, values, and
 * order. A query rendered as an `application/x-www-form-urlencoded` *body* is a separate
 * concern (it uses `+` for spaces) and is not what [encode] produces.
 *
 * ## Thread-safety
 *
 * [QueryParams] itself is immutable and therefore freely shareable. [Builder] is **not**
 * thread-safe — confine each builder instance to a single thread or guard externally.
 */
@Suppress("unused", "TooManyFunctions")
public class QueryParams private constructor(
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
     * if the name is absent. The returned list is a point-in-time snapshot exposed through
     * Kotlin's read-only [List] type.
     */
    public fun values(name: String): List<String> = paramsMap[name] ?: emptyList()

    /**
     * Returns `true` if any value is present for the given parameter name.
     */
    public fun contains(name: String): Boolean = paramsMap.containsKey(name)

    /**
     * Returns a defensive snapshot of all parameter names at the time of the call, in
     * insertion order, exposed through Kotlin's read-only [Set] type.
     */
    public fun names(): Set<String> = LinkedHashSet(paramsMap.keys)

    /**
     * Returns a defensive snapshot of all parameter entries at the time of the call.
     *
     * The snapshot is isolated from this [QueryParams] instance: mutating the builder it
     * came from cannot reach these entries. Each entry's value list and the entry set are
     * exposed through Kotlin's read-only [List] / [Set] types.
     */
    public fun entries(): Set<Map.Entry<String, List<String>>> {
        val snapshot = LinkedHashMap<String, List<String>>(paramsMap.size)
        paramsMap.forEach { (key, value) ->
            snapshot[key] = value
        }
        return snapshot.entries
    }

    /**
     * Returns `true` if no parameters are present.
     */
    public fun isEmpty(): Boolean = paramsMap.isEmpty()

    /**
     * Returns the total number of values across all names (not the number of names).
     *
     * `?a=1&a=2&b=3` reports `3`. Size is derived from the backing map rather than tracked,
     * so it always agrees with [entries].
     */
    public fun size(): Int = paramsMap.values.sumOf { it.size }

    /**
     * Renders this query string with RFC 3986 percent-encoding (space → `%20`, literal `+`
     * → `%2B`), via [PercentEncoding]. Insertion order is preserved; repeated names are emitted
     * once per value (`a=1&a=2`). Returns an empty string when there are no parameters.
     *
     * The leading `?` is **not** included — callers splice it in when assembling a URL.
     */
    public fun encode(): String {
        if (paramsMap.isEmpty()) return ""
        val sb = StringBuilder()
        paramsMap.forEach { (name, valueList) ->
            val encodedName = PercentEncoding.encodeComponent(name)
            valueList.forEach { value ->
                if (sb.isNotEmpty()) sb.append('&')
                sb.append(encodedName).append('=').append(PercentEncoding.encodeComponent(value))
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
     * Value equality over names and values **in order** (see the class note). Two instances
     * are equal iff they would [encode] identically.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueryParams) return false
        return paramsMap.entries.toList() == other.paramsMap.entries.toList()
    }

    override fun hashCode(): Int = paramsMap.entries.toList().hashCode()

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
         * Replaces all values for [name] with [values]. An empty [values] list drops the
         * parameter on [build] (a name with no values renders nothing), so it never leaves a
         * phantom entry; pass `null` to [set] to remove a parameter explicitly.
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
                params.paramsMap.forEach { (key, values) ->
                    paramsMap.getOrPut(key) { mutableListOf() }.addAll(values)
                }
            }

        /** Builds an immutable [QueryParams] from the builder's current state. */
        public fun build(): QueryParams {
            // Deep, defensive copy so later builder mutations cannot reach into the built
            // instance; the per-name value lists are isolated copies.
            //
            // A name whose value list is empty (e.g. `add(name, emptyList())`) contributes
            // nothing to encode(), so it is dropped here: keeping it would leave a phantom
            // entry that is contains()==true yet invisible to encode(), breaking the
            // "equal iff encode() identical" invariant. A value-less param keeps a single
            // empty-string value (`[""]`), which is not an empty list and is retained.
            val snapshot = LinkedHashMap<String, List<String>>(paramsMap.size)
            paramsMap.forEach { (key, values) ->
                if (values.isNotEmpty()) {
                    snapshot[key] = ArrayList(values)
                }
            }
            return QueryParams(snapshot)
        }
    }

    public companion object {
        /** Returns an empty builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Returns an empty [QueryParams]. */
        @JvmStatic
        public fun empty(): QueryParams = Builder().build()

        /**
         * Parses a raw query string (the part after `?`, without the leading `?`) into a
         * [QueryParams], decoding names and values with RFC 3986 semantics (space `%20`,
         * literal `+`) via [PercentEncoding].
         *
         * - A `null` or blank input yields an empty [QueryParams].
         * - A leading `?` is tolerated and stripped.
         * - `a=1&a=2` becomes a multi-valued `a`; `flag` (no `=`) becomes `flag` → `""`;
         *   `flag=` becomes `flag` → `""`.
         * - Empty segments (a stray `&`) are skipped.
         * - Malformed percent-encoding falls back to the raw text rather than throwing.
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
                    builder.add(PercentEncoding.decodeComponent(segment), "")
                } else {
                    val name = PercentEncoding.decodeComponent(segment.substring(0, eq))
                    val value = PercentEncoding.decodeComponent(segment.substring(eq + 1))
                    builder.add(name, value)
                }
            }
            return builder.build()
        }
    }
}
