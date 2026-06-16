/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

// Convention plugin for every module that is published to Maven Central. It carries the
// `maven-publish` + `signing` setup, the shared POM metadata, the staging repository, and the
// CI-gated signing configuration that was previously copied verbatim into all nine module
// build scripts.
//
// A module opts in with `plugins { id("dexpace.published-module") }`. The publication name,
// coordinates, POM, repository, and signing behaviour are then identical across modules; the
// `name`/`description` fields derive from `project.name`, so a module needs no further
// publishing configuration. A module that must NOT be published simply does not apply this
// plugin.

plugins {
    `maven-publish`
    signing
}

// Coordinates (`group`/`version`) are not set here. Gradle applies them from the repository-root
// `gradle.properties` to the root project and every subproject, so each consuming module already
// carries the shared `org.dexpace` coordinates and current version by the time this plugin runs —
// a coordinate bump is a one-line edit in that file.

// The `library` publication is built from the `java` software component, which only exists once a
// `java`/`java-library`/`kotlin("jvm")` plugin is applied. Every current consumer applies
// `kotlin("jvm")`, but this plugin neither applies nor requires one, so the whole publication +
// signing setup is guarded on the Kotlin JVM plugin. Without the guard, a module that opted in
// without a Java/Kotlin plugin would fail with an opaque "SoftwareComponent with name java not
// found".
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
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
            // Local staging repository. CI must override this to publish to a real remote.
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
            }
        }
    }

    signing {
        isRequired = (System.getenv("CI") == "true")
        val signingKey = project.findProperty("signing.key") as String? ?: System.getenv("SIGNING_KEY")
        val signingPassword =
            project.findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications["library"])
    }
}
