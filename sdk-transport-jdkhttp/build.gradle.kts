/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    // Publishing, signing, POM metadata, and coordinates come from this convention plugin
    // (build-logic/src/main/kotlin/dexpace.published-module.gradle.kts).
    id("dexpace.published-module")
}

// `java.net.http.HttpClient` was finalised in JEP 321 / Java 11. The root build script
// applies Java 8 to every Kotlin module; we override here. The output bytecode targets
// Java 11 — consumers MUST be on JDK 11 or newer to depend on this module. Both Kotlin
// and Java compile tasks must agree on the target; Gradle's JVM-target-validation rejects
// mismatches between `compileJava` and `compileKotlin`.
//
// Follow the same override pattern documented on `sdk-async-virtualthreads/build.gradle.kts`
// (S2.12 — Toolchain discipline): set `kotlin { jvmToolchain(11) }`,
// `java { sourceCompatibility / targetCompatibility = VERSION_11 + toolchain }`, AND override
// `compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }` so the root build's JVM_1_8 default
// is replaced for every Kotlin compile task in this module.
kotlin {
    jvmToolchain(11)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":sdk-core"))

    testImplementation(kotlin("test"))
    testImplementation(project(":sdk-io-okio3"))
    // MockWebServer ships in the OkHttp project but is a generic HTTP test server — it works
    // with any HTTP client, including `java.net.http.HttpClient`. The transport module under
    // test does NOT depend on OkHttp itself; only the test harness does.
    testImplementation(libs.okhttp.mockwebserver.junit5)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}

// Detekt analysis is disabled on this module because detekt 1.23.x (incl. 1.23.6 and 1.23.8)
// embeds a Kotlin compiler whose `org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse`
// throws `IllegalArgumentException: 25.0.2` when running on JDK 25+ — the system JDK on this
// machine. The underlying parser bug is fixed in Kotlin 2.1.20, but the 1.23.x line is pinned
// to Kotlin 2.0.x and will not receive the fix; the fix is only carried by detekt 2.0.0-alpha
// onwards. See https://github.com/detekt/detekt/issues/8714 (and duplicate #8980).
//
// We cannot work around this by pinning the task's JVM: detekt 1.23.x's `Detekt` task is a
// `SourceTask` that invokes analysis in-process via reflection (`DefaultCliInvoker`); it does
// not expose `javaLauncher`, `fork`, or any Worker-API toggle for choosing the JVM. Pinning
// the Gradle daemon JVM to 21 would change the daemon globally for every developer and is
// out of scope for this module-level workaround.
//
// Re-enable this block when:
//   (a) detekt ships a 1.23.x release embedding Kotlin >= 2.1.20, OR
//   (b) we adopt detekt 2.x (major version bump — see issue #8714 milestone).
tasks.matching { it.name == "detekt" }.configureEach {
    enabled = false
}
