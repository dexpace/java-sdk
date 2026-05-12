import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    // `apply false` brings the Kotlin plugin onto the classpath so each subproject can
    // declare `plugins { kotlin("jvm") }` without restating the version — but does not
    // apply Kotlin to the root project itself (which has no source).
    kotlin("jvm") version "2.3.21" apply false
}

group = "org.dexpace"
version = "0.0.1-alpha.1"


// TODO: Enable code linting with KtLint via a convention plugin (org.jlleitschuh.gradle.ktlint)
// TODO: Enforce code coverage with Kover via a convention plugin (org.jetbrains.kotlinx.kover)

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
        maven {
            // For maven snapshots
            url = URI.create("https://oss.sonatype.org/content/repositories/snapshots/")
        }
    }

    // Plugin application lives in each subproject's own `plugins {}` block — the old
    // `apply(plugin = ...)` form is discouraged by the Kotlin DSL. These callbacks fire
    // once the relevant plugin is actually applied in a subproject.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(8)
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
            }
        }

        dependencies {
            add("compileOnly", "org.slf4j:slf4j-api:2.0.17")
            add("implementation", "org.jetbrains.kotlin:kotlin-reflect")
        }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8

            toolchain {
                languageVersion.set(JavaLanguageVersion.of(8))
            }
        }
    }
}
