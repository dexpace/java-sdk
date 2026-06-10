/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
    signing
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

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

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("Dexpace Java SDK — ${project.name}")
                url.set("https://github.com/dexpace/java-sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/dexpace/java-sdk/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("dexpace")
                        name.set("Dexpace SDK Team")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/dexpace/java-sdk.git")
                    developerConnection.set("scm:git:ssh://github.com/dexpace/java-sdk.git")
                    url.set("https://github.com/dexpace/java-sdk")
                }
            }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

signing {
    isRequired = (System.getenv("CI") == "true")
    val signingKey = project.findProperty("signing.key") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = project.findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["library"])
}

// Detekt runs normally on this module. It uses the JDK-8 toolchain, so it is unaffected by
// the detekt 1.23.x `JavaVersion.parse` crash on non-8 toolchains (JDK 25+) that forces the
// gate off on `sdk-transport-jdkhttp` (11) and `sdk-async-virtualthreads` (21) — see those
// modules' build scripts and https://github.com/detekt/detekt/issues/8714. Keep this module
// in the gate; tune `config/detekt.yml` or add a narrow `@Suppress` for any genuine finding.
