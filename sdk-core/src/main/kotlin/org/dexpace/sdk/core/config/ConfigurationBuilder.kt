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
 * [Configuration.derive] to derive a reconfigured copy of an existing [Configuration].
 *
 * ## Thread-safety
 * Builders are *not* thread-safe — construct, configure, and [build] from a single thread. The
 * resulting [Configuration] is immutable and safe to share.
 */
public class ConfigurationBuilder : Builder<Configuration> {
    private val overrides = mutableMapOf<String, String>()
    private var envSource: Function<String, String?> = DEFAULT_ENV_SOURCE
    private var propsSource: Function<String, String?> = DEFAULT_PROPS_SOURCE

    /** Creates an empty builder with the default env / system-property lookup sources. */
    public constructor()

    /**
     * Creates a builder preloaded with [source]'s overrides and lookup sources. The override map is
     * copied, so mutating this builder never affects [source]. Used by [Configuration.newBuilder] and
     * [Configuration.derive].
     */
    public constructor(source: Configuration) {
        overrides.putAll(source.overrides)
        envSource = source.envSource
        propsSource = source.propsSource
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

    /**
     * Remove the explicit override for [name], if one is set. This drops only the override layer:
     * a later [Configuration.get] for [name] falls through to the environment-variable and
     * system-property seams (and finally the caller's default) exactly as if the override had never
     * been registered — it does **not** force the key to resolve to `null`. Removing a key that
     * carries no override is a no-op. As the inverse of [put], this is what lets
     * [Configuration.derive] un-pin an override inherited from the source instance.
     *
     * Kotlin's compiler-generated non-null parameter check raises `NullPointerException` when a
     * Java caller passes `null` for [name], so no explicit guard is needed here.
     */
    public fun remove(name: String): ConfigurationBuilder =
        apply {
            overrides.remove(name)
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
