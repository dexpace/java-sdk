plugins {
    kotlin("jvm")
    `java-test-fixtures`
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
    signing
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// repositories and shared compileOnly/implementation deps come from the root build.gradle.kts.

dependencies {
    // kotlin-reflect is used by ClientLogger (KClass constructor) and is not needed in other modules.
    implementation(libs.kotlin.reflect)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":sdk-core")))

    // SLF4J is `compileOnly` for the main source set; tests that reference SLF4J types
    // (FakeSlf4jLogger, ClientLoggerTest) need it on the compile classpath, and the
    // runtime needs an implementation so LoggerFactory can resolve a binding.
    testCompileOnly(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.nop)

    // Tests that need a real `IoProvider` (e.g. `MockResponse.Builder.body(String)`)
    // install `OkioIoProvider` from `:sdk-io-okio3`. The Okio adapter depends on
    // `:sdk-core`'s main classes only, so there is no cycle.
    testImplementation(project(":sdk-io-okio3"))
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
                        name.set("Proprietary")
                        url.set("https://github.com/dexpace/java-sdk/blob/main/LICENSE.md")
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
