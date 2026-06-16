/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

pluginManagement {
    // `build-logic` is an included build that compiles this repository's convention plugins.
    // Putting `includeBuild` here (rather than at the top level) lets modules apply those
    // plugins by id from their own `plugins {}` block — e.g. `id("dexpace.published-module")`.
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "java-sdk"

// gradle/libs.versions.toml is the single source of truth for dependency and plugin coordinates.
// Gradle auto-discovers this file — no explicit `versionCatalogs { create("libs") { from(...) } }`
// call is needed and would cause a "too many import invocation" error.

include("sdk-core")
include("sdk-io-okio3")
include("sdk-async-coroutines")
include("sdk-async-reactor")
include("sdk-async-netty")
include("sdk-async-virtualthreads")
include("sdk-transport-okhttp")
include("sdk-transport-jdkhttp")
include("sdk-serde-jackson")
