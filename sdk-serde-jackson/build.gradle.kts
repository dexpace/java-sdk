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

// Java 8 bytecode is inherited from the root build script — jvmToolchain(8) and
// jvmTarget=1.8 apply via plugins.withId("org.jetbrains.kotlin.jvm"). Jackson 2.18.x runs
// on JDK 8+, so no toolchain override is required.

dependencies {
    implementation(project(":sdk-core"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.datatype.jdk8)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}
