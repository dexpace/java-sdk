package org.dexpace.sdk.core.config

import java.util.function.Function

/**
 * Builder for [Configuration]. Use [put] to add explicit overrides (which win over env vars and
 * system properties), and [envSource] / [propsSource] as test seams to substitute the env / property
 * lookups with hermetic functions.
 *
 * ## Thread-safety
 * Builders are *not* thread-safe — construct, configure, and [build] from a single thread. The
 * resulting [Configuration] is immutable and safe to share.
 */
class ConfigurationBuilder {
    private val overrides = mutableMapOf<String, String>()
    private var envSource: Function<String, String?> = Function { name -> System.getenv(name) }
    private var propsSource: Function<String, String?> = Function { name -> System.getProperty(name) }

    /**
     * Register an explicit override. Overrides win over every other layer. Kotlin's
     * compiler-generated non-null parameter check raises `NullPointerException` when a Java
     * caller passes `null` for [name] or [value], so no explicit guard is needed here.
     */
    fun put(name: String, value: String): ConfigurationBuilder = apply {
        overrides[name] = value
    }

    /** Test seam: override the environment-variable source. */
    fun envSource(source: Function<String, String?>): ConfigurationBuilder = apply { envSource = source }

    /** Test seam: override the system-property source. */
    fun propsSource(source: Function<String, String?>): ConfigurationBuilder = apply { propsSource = source }

    /**
     * Materialize the immutable [Configuration]. The current override map is defensively copied so
     * subsequent [put] calls on this builder do not mutate the returned configuration.
     */
    fun build(): Configuration = Configuration(overrides.toMap(), envSource, propsSource)
}
