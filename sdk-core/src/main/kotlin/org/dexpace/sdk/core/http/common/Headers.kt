package org.dexpace.sdk.core.http.common

import java.util.Locale

/**
 * Represents a collection of HTTP headers.
 */
@Suppress("unused")
@ConsistentCopyVisibility
data class Headers private constructor(
    private val headersMap: Map<String, List<String>>
) {
    /**
     * Returns the first header value for the given name, or null if none.
     *
     * @param name the header name (case-insensitive)
     * @return the first header value, or null if not found
     */
    fun get(name: String): String? = headersMap[sanitizeName(name)]?.firstOrNull()

    /**
     * Returns all header values for the given name.
     *
     * @param name the header name (case-insensitive)
     * @return an unmodifiable list of header values, or an empty list if none
     */
    fun values(name: String): List<String> = headersMap[sanitizeName(name)] ?: emptyList()

    /**
     * Returns an unmodifiable set of all header names.
     *
     * @return an unmodifiable set of header names
     */
    fun names(): Set<String> = headersMap.keys

    /**
     * Returns an unmodifiable list of all header entries.
     *
     * @return an unmodifiable list of header entries as [Map.Entry]
     */
    fun entries(): Set<Map.Entry<String, List<String>>> = headersMap.entries

    /**
     * Returns a new [Builder] initialized with the existing headers.
     *
     * @return a new [Builder]
     */
    fun newBuilder(): Builder = Builder(this)

    override fun toString(): String = headersMap.toString()

    /**
     * Builder for constructing [Headers] instances.
     */
    class Builder {
        private val headersMap: MutableMap<String, MutableList<String>> = LinkedHashMap()

        /**
         * Creates a new builder
         */
        constructor()

        /**
         * Creates a new builder initialized with the headers from [headers].
         *
         * @param headers the headers to initialize from
         */
        constructor(headers: Headers) : this() {
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
        fun add(name: String, value: String): Builder = apply { add(sanitizeName(name), listOf(value)) }

        /**
         * Adds all header values for the specified name.
         *
         * @param name the header name
         * @param values the list of header values
         * @return this builder
         */
        fun add(name: String, values: List<String>): Builder = apply {
            headersMap.computeIfAbsent(sanitizeName(name)) { mutableListOf() }.addAll(values)
        }

        /**
         * Sets the header with the specified name to the single value provided.
         * If headers with this name already exist, they are removed.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        fun set(name: String, value: String): Builder = apply { set(sanitizeName(name), listOf(value)) }

        /**
         * Sets the header with the specified name to the values list provided.
         * If headers with this name already exist, they are removed.
         *
         * @param name the header name
         * @param values the header value
         * @return this builder
         */
        fun set(name: String, values: List<String>): Builder = apply {
            remove(sanitizeName(name))
            add(sanitizeName(name), values)
        }

        /**
         * Removes any header with the specified name.
         *
         * @param name the header name
         * @return this builder
         */
        fun remove(name: String): Builder = apply {
            headersMap.remove(sanitizeName(name))
        }

        /**
         * Builds an immutable [Headers] instance.
         *
         * @return the built [Headers]
         */
        fun build(): Headers = Headers(LinkedHashMap(headersMap))
    }

    companion object {
        @JvmStatic
        fun builder(headers: Headers): Builder = Builder(headers)

        @JvmStatic
        fun builder(): Builder = Builder()

        // TODO: Using Local.US all the time might be wrong, double check.
        private fun sanitizeName(value: String): String = value.lowercase(Locale.US).trim()
    }
}
