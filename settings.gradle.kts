plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "java-sdk"

// gradle/libs.versions.toml is the single source of truth for dependency and plugin coordinates.
// Gradle auto-discovers this file — no explicit `versionCatalogs { create("libs") { from(...) } }`
// call is needed and would cause a "too many import invocation" error.

include("sdk-core")
include("sdk-io-okio3")
include("sdk-async-coroutines")
include("sdk-async-reactor")
include("sdk-async-netty")
include("sdk-async-virtualthreads")
