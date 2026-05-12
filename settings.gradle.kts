plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "java-sdk"
include("sdk-core")
include("sdk-io-okio3")
include("sdk-async-coroutines")
include("sdk-async-reactor")
include("sdk-async-netty")
include("sdk-async-virtualthreads")