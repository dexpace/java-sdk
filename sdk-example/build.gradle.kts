/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    application
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// Java 8 bytecode and explicit-API strict mode are inherited from the root build script
// (jvmToolchain(8), jvmTarget=1.8, allWarningsAsErrors, explicitApi=Strict). The sample wires
// the Java-8 OkHttp transport, so it stays on the default toolchain — no override needed.
//
// Unlike the library modules this module applies NEITHER `maven-publish`/`signing` (it is a
// usage sample, never released) NOR the Kover plugin (it is intentionally outside the aggregate
// coverage floor — see the root build.gradle.kts rationale). The binary-compatibility validator
// also skips it via `apiValidation.ignoredProjects` in the root build.

application {
    mainClass.set("org.dexpace.sdk.example.ExampleAppKt")
}

dependencies {
    // Public contracts: HTTP models, the pipeline runtime + pillar steps, the I/O seam.
    implementation(project(":sdk-core"))
    // I/O adapter — the single `IoProvider` the sample installs at startup.
    implementation(project(":sdk-io-okio3"))
    // Transport adapter — the terminal `HttpClient` the pipeline dispatches to.
    implementation(project(":sdk-transport-okhttp"))
    // Serde adapter — typed request/response (de)serialization.
    implementation(project(":sdk-serde-jackson"))

    // MockWebServer ships in the OkHttp project as a generic embedded HTTP server. The sample
    // drives it from `main()` so the end-to-end exchange runs deterministically with no network.
    // The plain `mockwebserver3` artifact is used (not the `-junit5` variant): the sample manages
    // the server lifecycle by hand from `main()` and the smoke test, so no JUnit 5 extension — and
    // none of the JUnit it would drag onto the runtime classpath — is needed here.
    implementation(libs.okhttp.mockwebserver)
    // okhttp-tls generates a self-signed certificate so the embedded server can speak HTTPS — the
    // AUTH pillar step refuses to stamp credentials over plaintext, so the sample uses TLS exactly
    // as a production caller would. `OkHttpClient` is configured directly here, hence the explicit
    // dependency on OkHttp itself.
    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)

    // SLF4J is `compileOnly` on every Kotlin module (added by the root build); the sample needs a
    // real binding at runtime so the pipeline's instrumentation logging has somewhere to go. NOP
    // keeps the console output limited to what the sample prints itself.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
}
