plugins {
    kotlin("jvm")
    `java-test-fixtures`
    id("org.jetbrains.kotlinx.kover")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// repositories and shared compileOnly/implementation deps come from the root build.gradle.kts.

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":sdk-core")))

    // SLF4J is `compileOnly` for the main source set; tests that reference SLF4J types
    // (FakeSlf4jLogger, ClientLoggerTest) need it on the compile classpath, and the
    // runtime needs an implementation so LoggerFactory can resolve a binding.
    testCompileOnly("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")

    // Tests that need a real `IoProvider` (e.g. `MockResponse.Builder.body(String)`)
    // install `OkioIoProvider` from `:sdk-io-okio3`. The Okio adapter depends on
    // `:sdk-core`'s main classes only, so there is no cycle.
    testImplementation(project(":sdk-io-okio3"))
}

tasks.test {
    useJUnitPlatform()
}
