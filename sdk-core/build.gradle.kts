plugins {
    kotlin("jvm")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// repositories and shared compileOnly/implementation deps come from the root build.gradle.kts.

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
