/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.util.Collections
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
    public fun values(name: String): List<String> =
        headersMap[sanitizeName(name)]?.let(Collections::unmodifiableList) ?: emptyList()

    /**
     * Returns all header values for the given typed name.
     */
    public fun values(name: HttpHeaderName): List<String> =
        headersMap[name.caseInsensitiveName]?.let(Collections::unmodifiableList) ?: emptyList()

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
     * Returns an unmodifiable snapshot of all header entries at the time of the call.
     *
     * Each entry is an immutable copy whose value list is itself unmodifiable, so callers
     * cannot mutate this [Headers] instance through the returned set, its entries
     * ([Map.Entry.setValue]), or the per-name value lists — even by casting.
     *
     * @return an immutable snapshot of header entries as [Map.Entry]
     */
    public fun entries(): Set<Map.Entry<String, List<String>>> {
        val snapshot = LinkedHashMap<String, List<String>>(headersMap.size)
        headersMap.forEach { (key, value) ->
            snapshot[key] = Collections.unmodifiableList(value)
        }
        // `Collections.unmodifiableMap(...).entries` rejects both structural mutation
        // (add/remove/clear) and per-entry `setValue`, unlike `unmodifiableSet`, which
        // leaves `Map.Entry.setValue` open against the backing map.
        return Collections.unmodifiableMap(snapshot).entries
    }

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
                validateName(name)
                validateValues(name, values)
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
                validateValues(name.caseInsensitiveName, values)
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
                validateName(name)
                validateValues(name, values)
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
                validateValues(name.caseInsensitiveName, values)
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
        public fun build(): Headers {
            // Deep, defensive copy: snapshot each value list into its own unmodifiable
            // copy so that (a) later builder mutations cannot reach into the built
            // instance, and (b) the lists handed out by accessors cannot be mutated via
            // a cast to MutableList.
            val snapshot = LinkedHashMap<String, List<String>>(headersMap.size)
            headersMap.forEach { (key, values) ->
                snapshot[key] = Collections.unmodifiableList(ArrayList(values))
            }
            return Headers(snapshot)
        }
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

        /**
         * Rejects header values that would enable request/header splitting before they reach a
         * transport. A bare carriage return (`\r`) or line feed (`\n`) in a value lets an
         * attacker inject a new header or even a second request once the value is serialised;
         * OkHttp throws unchecked on such values and the JDK transport silently drops them, so we
         * validate here at the transport-agnostic model layer to fail fast and uniformly.
         *
         * Policy: reject **only** CR/LF, not OkHttp's stricter printable-ASCII-only rule. CR/LF
         * are the splitting vector and are illegal in every transport; tightening further would
         * reject legitimate UTF-8 values that some transports (and the JDK) accept, so the
         * conservative CR/LF check is the right model-layer contract.
         */
        private fun validateValues(
            name: String,
            values: List<String>,
        ) {
            values.forEach { value ->
                require(value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                    "Header value for '$name' must not contain a carriage return or line feed " +
                        "(\\r / \\n); such characters enable request/header splitting."
                }
            }
        }

        /**
         * Rejects header names that cannot legally appear on the wire before they reach a transport.
         *
         * An embedded carriage return (`\r`) or line feed (`\n`) in a name is the same
         * request/header-splitting vector guarded against for values: once the name is serialised an
         * attacker could inject a new header or a second request. A NUL or any other ASCII control
         * character is likewise illegal in an RFC 7230 field-name (`token`) and is rejected by — or
         * silently dropped at — the transport layer, so the two reference transports diverge (OkHttp
         * throws unchecked, the JDK transport drops the header) when such a name slips through.
         * Validating here at the transport-agnostic model layer fails fast and uniformly.
         *
         * Note [sanitizeName] trims surrounding whitespace and lower-cases, but it never removes an
         * *interior* control character, so this check is the only thing standing between a malformed
         * name and the transport. A blank name has no canonical form and is rejected as well.
         *
         * Policy: reject the C0 control range and DEL (code points `0x00`-`0x1F` and `0x7F`), which
         * covers CR, LF, and NUL. This is intentionally narrower than RFC 7230's full `tchar`
         * allow-list — restricting names to `tchar` only would reject some non-ASCII names that
         * certain transports accept, whereas the control-character set is illegal everywhere and
         * covers the splitting/injection surface.
         */
        private fun validateName(name: String) {
            require(name.isNotBlank()) { "Header name must not be blank." }
            name.forEach { ch ->
                require(ch.code > LAST_C0_CONTROL && ch.code != DEL_CONTROL) {
                    "Header name '$name' must not contain control characters " +
                        "(carriage return, line feed, NUL, or other C0/DEL bytes); " +
                        "such characters enable request/header splitting."
                }
            }
        }

        /** Highest code point in the C0 control range (US, `0x1F`); everything at or below is illegal. */
        private const val LAST_C0_CONTROL: Int = 0x1F

        /** The DEL control character (`0x7F`), the lone control code above the C0 range. */
        private const val DEL_CONTROL: Int = 0x7F
    }
}
