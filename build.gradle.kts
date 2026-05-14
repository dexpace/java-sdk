import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    // `apply false` brings the Kotlin plugin onto the classpath so each subproject can
    // declare `plugins { kotlin("jvm") }` without restating the version — but does not
    // apply Kotlin to the root project itself (which has no source).
    alias(libs.plugins.kotlin.jvm) apply false

    // Kover is applied to the root so it can aggregate coverage across subprojects. Each
    // subproject opts in via `plugins { id("org.jetbrains.kotlinx.kover") }`; the root
    // collates their `kover.xml` / `kover.html` reports into one combined view.
    alias(libs.plugins.kover)

    // Binary-compatibility-validator generates .api snapshot files per module so that
    // accidental public-API breakage is caught in CI. Run `./gradlew apiDump` to regenerate
    // the baseline after intentional API changes.
    alias(libs.plugins.binary.compatibility.validator)

    // ktlint enforces Kotlin style across all source sets.
    alias(libs.plugins.ktlint)

    // detekt performs static analysis across all source sets.
    alias(libs.plugins.detekt)
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

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
    kover(project(":sdk-transport-okhttp"))
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
                // Floor on aggregate LINE coverage. Branch coverage is reported but not gated.
                // The floor starts loose and is raised in dedicated coverage-PR commits.
                minBound(80)
            }
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
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
            // Explicit-api strict mode: every public declaration in the main source set must
            // specify a visibility modifier and (for non-trivial returns) an explicit return
            // type. The DSL form scopes this to the main source set — tests and test
            // fixtures keep Kotlin's default-public behaviour for terse `@Test fun foo() {}`.
            explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Strict
        }

        // NOTE: Any new module that REQUIRES JDK 21+ APIs (e.g. virtual threads, sequenced
        // collections) MUST override both `jvmToolchain(21)` in its kotlin/java extension
        // blocks AND set `compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }` in its own
        // build script. Failing to do so produces bytecode that references Java 21 stdlib
        // symbols but targets JVM_1_8 format — consumers on JDK 8 will see NoSuchMethodError
        // at runtime, and Gradle's JVM-target-validation will reject the mismatch.
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                allWarningsAsErrors.set(true)
            }
        }

        dependencies {
            add("compileOnly", libs.slf4j.api)
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

    // Reproducible archives: strip timestamps and sort archive entries so that
    // byte-for-byte identical JARs are produced from the same source across machines.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// Apply ktlint and detekt to every subproject via the root build. Both run with
// `ignoreFailures = false` — style violations and static-analysis findings break the
// build. Tune `config/detekt.yml` (or add narrow `@Suppress` annotations) when a rule
// needs adjustment rather than re-enabling the legacy report-only mode.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        ignoreFailures.set(false)
    }

    detekt {
        config.setFrom("$rootDir/config/detekt.yml")
        buildUponDefaultConfig = true
        ignoreFailures = false
    }
}
