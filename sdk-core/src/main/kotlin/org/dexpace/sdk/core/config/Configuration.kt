/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import java.time.Duration
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Function

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
 * Constructed via [ConfigurationBuilder]; derive a reconfigured copy of an existing instance with
 * [derive] or [newBuilder].
 *
 * ## Deriving a reconfigured copy (copy-on-write)
 * [derive] returns a **new** immutable [Configuration] with a mutator applied on top of this
 * one, leaving the receiver untouched:
 *
 * ```java
 * Configuration base = Configuration.builder().put("MAX_RETRY_ATTEMPTS", "3").build();
 * Configuration derived = base.derive(b -> b.put("LOG_LEVEL", "DEBUG"));
 * // base is unchanged; derived carries both overrides.
 * ```
 *
 * The derivation is copy-on-write in the value sense: the override map is copied so the original and
 * the derived instance never alias the same mutable state, while the [envSource]/[propsSource]
 * lookup functions are shared by reference (they are pure read seams and are never mutated). A
 * mutator that touches no override and replaces no source produces an independent instance equal in
 * behaviour to the original. [newBuilder] exposes the same prefilled builder for callers that prefer
 * to thread it through other builder-folding code before [ConfigurationBuilder.build].
 *
 * ## Thread-safety
 * Instances are immutable once built (the override map is copied) and safe to share across threads.
 * The process-wide global slot is published via `@Volatile`; readers observe the most-recently-set
 * configuration under last-write-wins semantics.
 */
public class Configuration internal constructor(
    @get:JvmSynthetic
    internal val overrides: Map<String, String>,
    @get:JvmSynthetic
    internal val envSource: Function<String, String?> = Function { name -> System.getenv(name) },
    @get:JvmSynthetic
    internal val propsSource: Function<String, String?> = Function { name -> System.getProperty(name) },
) {
    /**
     * Returns a fresh [ConfigurationBuilder] preloaded with this instance's overrides and lookup
     * sources. Mutating the returned builder never affects this [Configuration]; the override map is
     * copied up front. Use this when you want to thread the builder through other configuration code
     * before calling [ConfigurationBuilder.build]; prefer [derive] for the common
     * derive-in-one-call case.
     */
    public fun newBuilder(): ConfigurationBuilder = ConfigurationBuilder(this)

    /**
     * Derive a new immutable [Configuration] by applying [mutator] to a builder prefilled from this
     * instance, then building it. This [Configuration] is left unchanged (copy-on-write): the
     * override map is copied before [mutator] runs, so overrides added, replaced, or removed inside
     * [mutator] never leak back into the receiver. The env/property lookup seams are inherited by
     * reference.
     *
     * Kotlin's compiler-generated non-null parameter check raises `NullPointerException` when a Java
     * caller passes `null` for [mutator], so no explicit guard is needed here.
     */
    public fun derive(mutator: Consumer<ConfigurationBuilder>): Configuration {
        val builder = newBuilder()
        mutator.accept(builder)
        return builder.build()
    }

    /**
     * Look up a configuration value by [name].
     *
     * Order: explicit override -> environment variable (skipping empty strings) ->
     * system property (using the normalized name) -> [default].
     */
    @JvmOverloads
    public fun get(
        name: String,
        default: String? = null,
    ): String? {
        overrides[name]?.let { return it }
        val env = envSource.apply(name)
        if (!env.isNullOrEmpty()) return env
        return propsSource.apply(envToProp(name)) ?: default
    }

    /**
     * Look up a raw system-property value by its exact JVM property name, without the env-var
     * → property normalization applied by [get]. Use this for keys that live exclusively in
     * system-property form (e.g. `https.proxyHost`, `http.nonProxyHosts`) where the name's
     * casing must be preserved.
     *
     * Returns null when the property is unset.
     */
    public fun getProperty(name: String): String? = propsSource.apply(name)

    /**
     * Integer accessor. Returns [default] if the value is missing or not a valid integer.
     */
    public fun getInt(
        name: String,
        default: Int,
    ): Int = get(name)?.toIntOrNull() ?: default

    /**
     * Strict boolean accessor. Only `"true"` and `"false"` (case-insensitive) are recognized;
     * other values (including `"1"`, `"yes"`, `"on"`) fall through to [default].
     */
    public fun getBoolean(
        name: String,
        default: Boolean,
    ): Boolean {
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
    public fun getDuration(
        name: String,
        default: Duration,
    ): Duration = get(name)?.let { parseDuration(it) } ?: default

    public companion object {
        /**
         * Returns a fresh empty [ConfigurationBuilder]. Java-friendly entry point matching the
         * `builder()` idiom every other SDK model exposes; build from scratch with this, or derive a
         * reconfigured copy of an existing instance with [newBuilder] / [derive].
         */
        @JvmStatic
        public fun builder(): ConfigurationBuilder = ConfigurationBuilder()

        // Well-known keys. `const val` so callers reference them as `Configuration.MAX_RETRY_ATTEMPTS`
        // from both Kotlin and Java without going through `Companion`.

        /** Maximum number of retry attempts for retryable transport failures. */
        public const val MAX_RETRY_ATTEMPTS: String = "MAX_RETRY_ATTEMPTS"

        /** SLF4J-style log level for the SDK's [org.dexpace.sdk.core.instrumentation.ClientLogger]. */
        public const val LOG_LEVEL: String = "LOG_LEVEL"

        /** Standard environment variable for the plain-HTTP proxy URL (`http://user:pass@host:port`). */
        public const val HTTP_PROXY: String = "HTTP_PROXY"

        /** Standard environment variable for the HTTPS proxy URL, preferred over [HTTP_PROXY]. */
        public const val HTTPS_PROXY: String = "HTTPS_PROXY"

        /** Standard environment variable for the comma-separated no-proxy host list. */
        public const val NO_PROXY: String = "NO_PROXY"

        @Volatile
        private var global: Configuration = Configuration(emptyMap())

        /** Returns the process-wide global configuration. Defaults to an empty configuration. */
        @JvmStatic
        public fun getGlobalConfiguration(): Configuration = global

        /**
         * Replace the process-wide global configuration. Last-write-wins via `@Volatile`.
         * Kotlin's compiler-generated non-null parameter check raises `NullPointerException`
         * (with a kotlin-style "Parameter specified as non-null is null" message) when a
         * Java caller passes `null`, so no explicit guard is needed here.
         */
        @JvmStatic
        public fun setGlobalConfiguration(c: Configuration) {
            global = c
        }

        /** Convert `MAX_RETRY_ATTEMPTS` -> `max.retry.attempts` for the system-property lookup. */
        internal fun envToProp(name: String): String = name.lowercase(Locale.US).replace('_', '.')

        /**
         * Parse a duration string; returns `null` on failure. Supports ISO-8601 and shorthand.
         *
         * Guard-clause parser with one return per failure mode (empty input, ISO-8601 path,
         * numeric-parse failure, negative-value reject, terminal when). Collapsing would
         * require a mutable accumulator and obscure the flow.
         */
        @Suppress("ReturnCount")
        internal fun parseDuration(raw: String): Duration? {
            if (raw.isEmpty()) return null
            // ISO-8601 path: `PT5S`, `P1D`, etc. Reject negative durations (e.g. `PT-5S`) for the
            // same reason the shorthand path does below — downstream consumers (Clock.sleep,
            // Futures.delay) assume a non-negative duration and throw on a negative one.
            if (Character.toUpperCase(raw[0]) == 'P') {
                return try {
                    val d = Duration.parse(raw)
                    if (d.isNegative) null else d
                } catch (_: Exception) {
                    null
                }
            }
            // Shorthand: <number><unit>. Reject negatives — durations are always non-negative
            // and a "-5s" shorthand is almost certainly a configuration bug.
            val unit = raw.takeLastWhile { !it.isDigit() }
            val numPart = raw.substring(0, raw.length - unit.length).trim()
            val n = numPart.toLongOrNull() ?: return null
            if (n < 0) return null
            return when (unit.lowercase(Locale.US).trim()) {
                "", "ms" -> Duration.ofMillis(n)
                "s" -> Duration.ofSeconds(n)
                "m" -> Duration.ofMinutes(n)
                "h" -> Duration.ofHours(n)
                "d" -> Duration.ofDays(n)
                else -> null
            }
        }
    }
}
