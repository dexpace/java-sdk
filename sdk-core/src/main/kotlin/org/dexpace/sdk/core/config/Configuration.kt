package org.dexpace.sdk.core.config

import java.time.Duration
import java.util.Locale

/**
 * Layered runtime configuration: explicit override -> environment variable -> system property -> default.
 *
 * Environment variables are looked up by the given name (e.g. `MAX_RETRY_ATTEMPTS`). System properties are
 * looked up by the normalized form (e.g. `max.retry.attempts`). Empty environment values (`EXAMPLE=`) are
 * treated as absent so the system-property layer gets a chance to supply the value.
 *
 * Typed accessors (`getInt`, `getBoolean`, `getDuration`) return the provided default on parse failures —
 * configuration issues never throw at the lookup site.
 *
 * Constructed via [ConfigurationBuilder].
 */
class Configuration internal constructor(
    private val overrides: Map<String, String>,
    private val envSource: (String) -> String? = { name -> System.getenv(name) },
    private val propsSource: (String) -> String? = { name -> System.getProperty(name) },
) {

    /**
     * Look up a configuration value by [name].
     *
     * Order: explicit override -> environment variable (skipping empty strings) ->
     * system property (using the normalized name) -> [default].
     */
    @JvmOverloads
    fun get(name: String, default: String? = null): String? {
        overrides[name]?.let { return it }
        val env = envSource(name)
        if (env != null && env.isNotEmpty()) return env
        val prop = propsSource(envToProp(name))
        if (prop != null) return prop
        return default
    }

    /**
     * Integer accessor. Returns [default] if the value is missing or not a valid integer.
     */
    fun getInt(name: String, default: Int): Int = get(name)?.toIntOrNull() ?: default

    /**
     * Strict boolean accessor. Only `"true"` and `"false"` (case-insensitive) are recognized;
     * other values (including `"1"`, `"yes"`, `"on"`) fall through to [default].
     */
    fun getBoolean(name: String, default: Boolean): Boolean {
        val raw = get(name) ?: return default
        return when (raw.lowercase(Locale.US)) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    /**
     * Duration accessor. Supports ISO-8601 (`PT5S`, `P1D`) and shorthand (`500ms`, `5s`, `1m`, `2h`, `1d`).
     * A bare number is interpreted as milliseconds (`1000` -> 1 second).
     * Returns [default] on parse failure.
     */
    fun getDuration(name: String, default: Duration): Duration =
        get(name)?.let { parseDuration(it) } ?: default

    companion object {
        // Well-known keys. `const val` so callers reference them as `Configuration.MAX_RETRY_ATTEMPTS`
        // from both Kotlin and Java without going through `Companion`.
        const val MAX_RETRY_ATTEMPTS: String = "MAX_RETRY_ATTEMPTS"
        const val LOG_LEVEL: String = "LOG_LEVEL"
        const val HTTP_PROXY: String = "HTTP_PROXY"
        const val HTTPS_PROXY: String = "HTTPS_PROXY"
        const val NO_PROXY: String = "NO_PROXY"

        @Volatile
        private var global: Configuration = Configuration(emptyMap())

        @JvmStatic
        fun getGlobalConfiguration(): Configuration = global

        /**
         * Replace the process-wide global configuration. Last-write-wins via `@Volatile`.
         * @throws NullPointerException if [c] is null.
         */
        @JvmStatic
        fun setGlobalConfiguration(c: Configuration) {
            @Suppress("SENSELESS_COMPARISON")
            if (c == null) throw NullPointerException("Configuration must not be null")
            global = c
        }

        /** Convert `MAX_RETRY_ATTEMPTS` -> `max.retry.attempts` for the system-property lookup. */
        internal fun envToProp(name: String): String =
            name.lowercase(Locale.US).replace('_', '.')

        /** Parse a duration string; returns `null` on failure. Supports ISO-8601 and shorthand. */
        internal fun parseDuration(raw: String): Duration? {
            if (raw.isEmpty()) return null
            // ISO-8601 path: `PT5S`, `P1D`, etc.
            val upper = if (raw.length >= 1) Character.toUpperCase(raw[0]) else ' '
            if (upper == 'P') {
                return try {
                    Duration.parse(raw)
                } catch (_: Exception) {
                    null
                }
            }
            // Shorthand: <number><unit>
            val unit = raw.takeLastWhile { !it.isDigit() }
            val numPart = raw.substring(0, raw.length - unit.length).trim()
            val n = numPart.toLongOrNull() ?: return null
            return when (unit.lowercase(Locale.US).trim()) {
                "ms" -> Duration.ofMillis(n)
                "s" -> Duration.ofSeconds(n)
                "m" -> Duration.ofMinutes(n)
                "h" -> Duration.ofHours(n)
                "d" -> Duration.ofDays(n)
                "" -> Duration.ofMillis(n)
                else -> null
            }
        }
    }
}
