plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

dependencies {
    implementation(project(":sdk-core"))
    // Reactor itself is Java 8 compatible and ships with `Mono.fromFuture(...)` / `Mono.toFuture()`
    // — no extra adapter library required to bridge `CompletableFuture` to `Mono`/`Flux`.
    implementation("io.projectreactor:reactor-core:3.7.0")

    testImplementation(kotlin("test"))
    testImplementation("io.projectreactor:reactor-test:3.7.0")
    // SSE tests use the real OkioIoProvider as the input source — the okio adapter only
    // takes a dependency on sdk-core, so there's no module cycle.
    testImplementation(project(":sdk-io-okio3"))
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
