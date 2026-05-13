plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// repositories come from the root build.gradle.kts.
// jvmToolchain(8) is inherited from the root via plugins.withId("org.jetbrains.kotlin.jvm").
// We intentionally do NOT override it here: a higher toolchain would let our code reference
// Java 9+ stdlib APIs that aren't present at runtime on JDK 8 consumers — `jvmTarget=1.8`
// alone governs bytecode format, not the API surface.

dependencies {
    implementation(project(":sdk-core"))
    implementation("com.squareup.okio:okio:3.17.0")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":sdk-core")))
}

tasks.test {
    useJUnitPlatform()
}


