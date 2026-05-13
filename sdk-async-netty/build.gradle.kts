plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

dependencies {
    implementation(project(":sdk-core"))
    // `netty-common` carries `io.netty.util.concurrent.Future`/`Promise`/`EventExecutor` —
    // the smallest surface needed to bridge `CompletableFuture` ↔ Netty futures without
    // pulling in the transport layer (`netty-handler`, `netty-codec`, etc.).
    implementation("io.netty:netty-common:4.2.13.Final")

    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-api:2.0.18")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.18")
}

tasks.test {
    useJUnitPlatform()
}
