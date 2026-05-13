import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// Virtual threads require JDK 21+. The root build script applies Java 8 to every Kotlin
// module; we override here. The output bytecode targets Java 21 — consumers MUST be on
// JDK 21 or newer to depend on this module. Both Kotlin and Java compile tasks must agree
// on the target; Gradle's JVM-target-validation rejects mismatches between `compileJava`
// and `compileKotlin`.
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
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
