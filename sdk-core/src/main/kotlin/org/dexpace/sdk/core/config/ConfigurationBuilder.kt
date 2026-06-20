/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import org.dexpace.sdk.core.generics.Builder
import java.util.function.Function

/**
 * Builder for [Configuration]. Use [put] to add explicit overrides (which win over env vars and
 * system properties), and [envSource] / [propsSource] as test seams to substitute the env / property
 * lookups with hermetic functions.
 *
 * Implements the generic [Builder] contract so it can be folded by builder-style configuration code.
 * Construct empty for a configuration built from scratch, or via [Configuration.newBuilder] /
 * [Configuration.withOptions] to derive a reconfigured copy of an existing [Configuration].
 *
 * ## Thread-safety
 * Builders are *not* thread-safe — construct, configure, and [build] from a single thread. The
 * resulting [Configuration] is immutable and safe to share.
 */
public class ConfigurationBuilder : Builder<Configuration> {
    private val overrides = mutableMapOf<String, String>()
    private var envSource: Function<String, String?> = Function { name -> System.getenv(name) }
    private var propsSource: Function<String, String?> = Function { name -> System.getProperty(name) }

    /** Creates an empty builder with the default env / system-property lookup sources. */
    public constructor()

    /**
     * Creates a builder preloaded with [source]'s overrides and lookup sources. The override map is
     * copied, so mutating this builder never affects [source]. Used by [Configuration.newBuilder] and
     * [Configuration.withOptions].
     */
    public constructor(source: Configuration) {
        overrides.putAll(source.overridesSnapshot())
        envSource = source.envSource()
        propsSource = source.propsSource()
    }

    /**
     * Register an explicit override. Overrides win over every other layer. Kotlin's
     * compiler-generated non-null parameter check raises `NullPointerException` when a Java
     * caller passes `null` for [name] or [value], so no explicit guard is needed here.
     */
    public fun put(
        name: String,
        value: String,
    ): ConfigurationBuilder =
        apply {
            overrides[name] = value
        }

    /** Test seam: override the environment-variable source. */
    public fun envSource(source: Function<String, String?>): ConfigurationBuilder = apply { envSource = source }

    /** Test seam: override the system-property source. */
    public fun propsSource(source: Function<String, String?>): ConfigurationBuilder = apply { propsSource = source }

    /**
     * Materialize the immutable [Configuration]. The current override map is defensively copied so
     * subsequent [put] calls on this builder do not mutate the returned configuration.
     */
    override fun build(): Configuration = Configuration(overrides.toMap(), envSource, propsSource)
}
