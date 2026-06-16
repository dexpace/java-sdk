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
    // Style-gate this included build's own scripts. `build-logic` is a separate build with its
    // own settings, so the root build's `subprojects { ktlint }` block does not reach it; without
    // this the convention-plugin `.kts` files would escape the repository's Kotlin style checks.
    // The version is pinned literally (not via the version catalog) to keep this build catalog-free
    // — see the rationale in `settings.gradle.kts`; it must match `ktlint-plugin` in
    // `gradle/libs.versions.toml`.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Pin the toolchain so this included build compiles reproducibly regardless of the JDK running
// the Gradle daemon. This is plugin code for the build JVM, not shipped bytecode, so the version
// only needs to be recent enough for `kotlin-dsl`.
kotlin {
    jvmToolchain(21)
}

ktlint {
    ignoreFailures.set(false)
}

// `kotlin-dsl` adds its plugin wrappers and DSL accessors (under build/generated-sources) to the
// `main` Kotlin source set, so the source-set ktlint tasks would otherwise lint tool-generated
// code. Drop the generated tree from those tasks' inputs; the hand-written convention scripts under
// src/ — and the build script via `runKtlintCheckOverKotlinScripts` — are still checked.
val generatedSourcesDir = layout.buildDirectory.dir("generated-sources").get().asFile
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    val handWritten = source.filter { file -> !file.startsWith(generatedSourcesDir) }.files
    setSource(handWritten)
}
