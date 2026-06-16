/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    // Publishing, signing, POM metadata, and coordinates come from this convention plugin
    // (build-logic/src/main/kotlin/dexpace.published-module.gradle.kts).
    id("dexpace.published-module")
}

// Java 8 bytecode is inherited from the root build script — this transport ships to JDK 8
// consumers just like `sdk-core` does. OkHttp 5.x itself supports JDK 8+ at runtime.
//
// The Okio dependency that OkHttp drags in transitively is referenced only inside the
// request-body adapters: `SdkRequestBodyAdapter` bridges a generic body through
// `okio.BufferedSink.outputStream()` + `IoProvider.sink(OutputStream)`, while
// `FileRequestBodyAdapter` uses okio's `FileSystem`/`FileHandle` for a zero-copy file
// upload. Adapter code elsewhere in the module stays clear of okio types.

dependencies {
    implementation(project(":sdk-core"))
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(project(":sdk-io-okio3"))
    testImplementation(libs.okhttp.mockwebserver.junit5)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}

// Detekt runs normally on this module. It uses the JDK-8 toolchain, so it is unaffected by
// the detekt 1.23.x `JavaVersion.parse` crash on non-8 toolchains (JDK 25+) that forces the
// gate off on `sdk-transport-jdkhttp` (11) and `sdk-async-virtualthreads` (21) — see those
// modules' build scripts and https://github.com/detekt/detekt/issues/8714. Keep this module
// in the gate; tune `config/detekt.yml` or add a narrow `@Suppress` for any genuine finding.
