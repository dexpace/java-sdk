plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // sdk-core references SLF4J via `compileOnly`; tests that load sdk-core classes need a
    // binding on the runtime classpath. `slf4j-nop` is the same one sdk-core's own tests use.
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
