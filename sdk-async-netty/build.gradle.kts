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
    // `netty-common` carries `io.netty.util.concurrent.Future`/`Promise`/`EventExecutor` —
    // the smallest surface needed to bridge `CompletableFuture` ↔ Netty futures without
    // pulling in the transport layer (`netty-handler`, `netty-codec`, etc.).
    implementation(libs.netty.common)

    testImplementation(kotlin("test"))
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
