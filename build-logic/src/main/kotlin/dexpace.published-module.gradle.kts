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

// Coordinates. The group and version are the same for every published module and match the
// values declared on the root project; keeping the single literal here makes a coordinate
// change a one-file edit instead of a nine-file edit.
group = "org.dexpace"
version = "0.0.1-alpha.1"

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
    val signingPassword = project.findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["library"])
}
