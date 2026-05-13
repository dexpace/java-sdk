plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
    signing
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

dependencies {
    implementation(project(":sdk-core"))
    // Reactor itself is Java 8 compatible and ships with `Mono.fromFuture(...)` / `Mono.toFuture()`
    // — no extra adapter library required to bridge `CompletableFuture` to `Mono`/`Flux`.
    implementation(libs.reactor.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.reactor.test)
    // SSE tests use the real OkioIoProvider as the input source — the okio adapter only
    // takes a dependency on sdk-core, so there's no module cycle.
    testImplementation(project(":sdk-io-okio3"))
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
                // TODO: set url, licenses, developers, scm when publishing to a public repo
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
    isRequired = false
    sign(publishing.publications["library"])
}
