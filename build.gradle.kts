import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    kotlin("jvm") version "2.3.0"
}

group = "org.dexpace"
version = "0.0.1-alpha.1"


allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java")

    // TODO: Enable code linting with KtLint
    // apply(plugin = "org.jlleitschuh.gradle.ktlint")

    //  TODO: Enforce code coverage with Kover
    //  apply(plugin = "org.jetbrains.kotlinx.kover")

    repositories {
        mavenCentral()
        mavenLocal()
        maven {
            // For maven snapshots
            url = URI.create("https://oss.sonatype.org/content/repositories/snapshots/")
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        kotlin {
            jvmToolchain(8)
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    java {
        withSourcesJar()
        withJavadocJar()
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8

        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    }

    dependencies {
        /* Logging */
        compileOnly("org.slf4j:slf4j-api:2.0.17")
    }
}
