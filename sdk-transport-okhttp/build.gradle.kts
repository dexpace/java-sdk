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
// The Okio dependency that OkHttp drags in transitively is intentionally NOT referenced
// directly from our adapter code: we bridge through `okio.BufferedSink.outputStream()` +
// `IoProvider.sink(OutputStream)` so this module keeps a single import of okio types
// (`okio.BufferedSink` and `okio.MediaType`) confined to the request-body adapter.

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
