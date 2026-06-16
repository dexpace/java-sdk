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

// repositories come from the root build.gradle.kts.
// jvmToolchain(8) is inherited from the root via plugins.withId("org.jetbrains.kotlin.jvm").
// We intentionally do NOT override it here: a higher toolchain would let our code reference
// Java 9+ stdlib APIs that aren't present at runtime on JDK 8 consumers — `jvmTarget=1.8`
// alone governs bytecode format, not the API surface.

dependencies {
    implementation(project(":sdk-core"))
    implementation(libs.okio)
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":sdk-core")))
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}
