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

dependencies {
    implementation(project(":sdk-core"))
    // Reactor itself is Java 8 compatible and ships with `Mono.fromFuture(...)` / `Mono.toFuture()`
    // — no extra adapter library required to bridge `CompletableFuture` to `Mono`/`Flux`.
    implementation(libs.reactor.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.reactor.test)
    // SSE tests use the real OkioIoProvider as the input source — the okio adapter only
    // takes a dependency on sdk-core, so there's no module cycle.
    testImplementation(project(":sdk-io-okio3"))
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}
