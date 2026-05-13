import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    // `apply false` brings the Kotlin plugin onto the classpath so each subproject can
    // declare `plugins { kotlin("jvm") }` without restating the version — but does not
    // apply Kotlin to the root project itself (which has no source).
    kotlin("jvm") version "2.3.21" apply false
    // Kover is applied to the root so it can aggregate coverage across subprojects. Each
    // subproject opts in via `plugins { id("org.jetbrains.kotlinx.kover") }`; the root
    // collates their `kover.xml` / `kover.html` reports into one combined view.
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "org.dexpace"
version = "0.0.1-alpha.1"


// TODO: Enable code linting with KtLint via a convention plugin (org.jlleitschuh.gradle.ktlint)

// Coverage: aggregate every Kover-enabled subproject through this root project's reports.
// The legacy Java compat tree (`sdk-core/src/main/java/`) is excluded — it is generated
// code that backs service clients and is not part of the SDK's hand-written surface.
dependencies {
    kover(project(":sdk-core"))
    kover(project(":sdk-io-okio3"))
    kover(project(":sdk-async-coroutines"))
    kover(project(":sdk-async-reactor"))
    kover(project(":sdk-async-netty"))
    kover(project(":sdk-async-virtualthreads"))
}

kover {
    reports {
        filters {
            excludes {
                // Generated service-client compat layer (sdk-core/src/main/java/).
                // These classes are compiled into BARE packages (no org.dexpace prefix) —
                // "annotations.*" is the fully-qualified name pattern, not a suffix glob.
                // Kotlin SDK classes live under org.dexpace.sdk.core.* and are unaffected
                // by these patterns.
                classes(
                    "annotations.*",
                    "binarydata.*",
                    "credentials.*",
                    "http.*",
                    "implementation.*",
                    "instrumentation.*",
                    "models.*",
                    "serialization.*",
                    "traits.*",
                    "utils.*",
                )
                // Test fixtures (test-only support code, fully-qualified org.dexpace namespace).
                classes("org.dexpace.sdk.core.testing.*")
            }
        }
        verify {
            rule {
                // Coverage gate: line + branch coverage must stay above the floor. The
                // floor starts loose and is raised in dedicated coverage-PR commits.
                minBound(80)
            }
        }
    }
}

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
