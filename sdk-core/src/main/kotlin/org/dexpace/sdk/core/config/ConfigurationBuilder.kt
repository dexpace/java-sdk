package org.dexpace.sdk.core.config

/**
 * Builder for [Configuration]. Use [put] to add explicit overrides (which win over env vars and
 * system properties), and [envSource] / [propsSource] as test seams to substitute the env / property
 * lookups with hermetic functions.
 */
class ConfigurationBuilder {
    private val overrides = mutableMapOf<String, String>()
    private var envSource: (String) -> String? = { name -> System.getenv(name) }
    private var propsSource: (String) -> String? = { name -> System.getProperty(name) }

    /**
     * Register an explicit override. Overrides win over every other layer.
     * @throws NullPointerException if [name] or [value] is null.
     */
    fun put(name: String, value: String): ConfigurationBuilder = apply {
        @Suppress("SENSELESS_COMPARISON")
        if (name == null) throw NullPointerException("name must not be null")
        @Suppress("SENSELESS_COMPARISON")
        if (value == null) throw NullPointerException("value must not be null")
        overrides[name] = value
    }

    /** Test seam: override the environment-variable source. */
    fun envSource(source: (String) -> String?): ConfigurationBuilder = apply { envSource = source }

    /** Test seam: override the system-property source. */
    fun propsSource(source: (String) -> String?): ConfigurationBuilder = apply { propsSource = source }

    fun build(): Configuration = Configuration(overrides.toMap(), envSource, propsSource)
}
