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
    // `netty-common` carries `io.netty.util.concurrent.Future`/`Promise`/`EventExecutor` —
    // the smallest surface needed to bridge `CompletableFuture` ↔ Netty futures without
    // pulling in the transport layer (`netty-handler`, `netty-codec`, etc.).
    implementation(libs.netty.common)

    testImplementation(kotlin("test"))
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}
