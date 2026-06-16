/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

// The `kotlin-dsl` plugin lets this build compile precompiled script plugins — every
// `src/main/kotlin/*.gradle.kts` file becomes a plugin whose id is its file name minus the
// `.gradle.kts` suffix (e.g. `dexpace.published-module`). Consumers in the main build apply
// it by that id once `settings.gradle.kts` has `includeBuild("build-logic")` on the plugin
// classpath.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}
