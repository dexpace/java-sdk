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

// Java 8 bytecode is inherited from the root build script — the module ships to JDK 8 consumers
// just like `sdk-core` does.

dependencies {
    implementation(project(":sdk-core"))
    // `kotlinx-coroutines-core` provides `suspend` machinery; `-jdk8` adds the
    // `CompletableFuture.await()` extension and `coroutineScope.future { ... }` builder that
    // bridge in both directions between coroutines and `CompletableFuture`.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.coroutines.slf4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // slf4j-api is testImplementation (not testRuntimeOnly) so MDC tests can reference
    // org.slf4j.MDC and org.slf4j.helpers.BasicMDCAdapter at compile time. slf4j-nop is
    // the runtime binding; MDC functionality in tests is provided via the reflection-
    // installed BasicMDCAdapter (see installBasicMdcAdapter() in each test file).
    testImplementation(libs.slf4j.api)
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
