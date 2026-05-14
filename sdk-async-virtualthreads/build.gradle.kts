import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
    signing
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// Virtual threads require JDK 21+. The root build script applies Java 8 to every Kotlin
// module; we override here. The output bytecode targets Java 21 — consumers MUST be on
// JDK 21 or newer to depend on this module. Both Kotlin and Java compile tasks must agree
// on the target; Gradle's JVM-target-validation rejects mismatches between `compileJava`
// and `compileKotlin`.
//
// S2.12 — Toolchain discipline: this is the canonical override for JDK 21+ modules. If you
// add another module that requires JDK 21+ APIs, copy this pattern exactly: set both
// kotlin { jvmToolchain(21) } and java { sourceCompatibility / targetCompatibility } AND
// override compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } below. The root build's
// plugins.withId("org.jetbrains.kotlin.jvm") callback targets JVM_1_8 — any module that
// does NOT override will silently produce JDK-8-compatible bytecode.
kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":sdk-core"))
    testImplementation(kotlin("test"))
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
