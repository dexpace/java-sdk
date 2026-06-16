/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

plugins {
    // Kotlin only — no kover, no maven-publish, no signing. This module produces no published
    // artifact and contributes no coverage; it is a build-time regression guard. The root build
    // therefore does NOT add it to the kover aggregate, and `apiValidation.ignoredProjects`
    // (root build.gradle.kts) keeps it out of the binary-compatibility snapshot. Explicit-API
    // strict mode and ktlint are inherited from the root build and left ON; detekt is disabled
    // below for the same JDK-25-toolchain reason as the other non-Java-8 modules.
    kotlin("jvm")
}

group = "org.dexpace"
version = "0.0.1-alpha.1"

// This module targets JDK 11, not the root's Java-8 default. It depends on sdk-transport-jdkhttp
// (Java-11 bytecode) so it can exercise that transport through R8, and the whole shrink pipeline
// already runs on a JDK 11 (R8 itself is Java-11 bytecode, and the shrunk program runs on the same
// 11 launcher) — so a downstream consumer that uses the jdkhttp transport is, by construction, an
// 11+ consumer. Override all three knobs the way sdk-transport-jdkhttp documents (S2.12 — Toolchain
// discipline): the toolchain, the `java {}` source/target, and every Kotlin compile task's target.
kotlin {
    jvmToolchain(11)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Detekt is disabled here for the same reason as sdk-transport-jdkhttp and sdk-async-virtualthreads:
// detekt 1.23.x crashes parsing the JDK 25+ system toolchain version when a module targets a non-8
// toolchain (detekt/detekt#8714). Re-enable when detekt 1.23.x embeds Kotlin >= 2.1.20 or the build
// moves to detekt 2.x. ktlint and explicit-API strict mode stay on.
tasks.matching { it.name == "detekt" }.configureEach {
    enabled = false
}

// The shrink harness exercises the SDK exactly as a downstream consumer would: it depends on the
// published modules (core, the Okio I/O adapter, both reference transports, the Jackson serde) and
// their real transitive runtime dependencies, then bundles the lot into a single program for R8
// to shrink. MockWebServer drives a genuine in-process HTTP round-trip through each transport so
// the shrunk program proves they still work end-to-end, not merely that their classes survived.
//
// Both transports are included so the harness guards each one's shipped keep-rules symmetrically:
// dropping a rule from either sdk-transport-okhttp or sdk-transport-jdkhttp would fail this module.
// Depending on the Java-11 jdkhttp module is why this module itself targets JDK 11 (see above).
dependencies {
    implementation(project(":sdk-core"))
    implementation(project(":sdk-io-okio3"))
    implementation(project(":sdk-transport-okhttp"))
    implementation(project(":sdk-transport-jdkhttp"))
    implementation(project(":sdk-serde-jackson"))
    implementation(libs.okhttp.mockwebserver.junit5)

    // SLF4J is compileOnly in the SDK, so a real consumer supplies the API plus a binding at
    // runtime. The no-op binding (which transitively brings slf4j-api) is the lightest choice and
    // matches what every transport test runtime already uses; it must be bundled into the shrink
    // input jar or the shrunk program fails with NoClassDefFoundError: org/slf4j/LoggerFactory.
    runtimeOnly(libs.slf4j.nop)

    // R8 itself runs as an external tool (see the r8Shrink task), so it lives in its own resolvable
    // configuration rather than on the program classpath.
}

// ---------------------------------------------------------------------------------------------
// R8 shrink-survival pipeline
//
//   buildShrinkInputJar  -> fat jar: consumer program + SDK + okio + transports + jackson + stdlib
//   r8Shrink             -> runs R8 in full mode over that jar with the SHIPPED consumer rules
//   r8Run                -> runs the shrunk program and asserts it prints the success sentinel
//
// r8Run is wired into `check`, so a plain `./gradlew build` proves shrink-survival.
// ---------------------------------------------------------------------------------------------

// Isolated configuration holding only the R8 tool jar, fetched from Google's Maven repo (added
// to the root allprojects { repositories } block). Keeping it separate means R8's own (large)
// dependency closure never leaks onto the program classpath that gets shrunk.
val r8Tool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    r8Tool(libs.r8)
}

val shrinkBuildDir: Provider<Directory> = layout.buildDirectory.dir("r8")
val shrinkInputJar: Provider<RegularFile> = shrinkBuildDir.map { it.file("consumer-all.jar") }
val shrunkJar: Provider<RegularFile> = shrinkBuildDir.map { it.file("consumer-shrunk.jar") }

// Kept in sync with ShrinkSurvivalApp.SUCCESS_SENTINEL. The build script cannot reference the
// project's own compiled classes, so the literal is duplicated here; the app prints it and the
// harness greps for it.
val successSentinel = "SHRINK-SURVIVAL-OK"

// The consumer rules each SDK module ships under META-INF/proguard. The harness feeds these to R8
// explicitly (rather than relying on R8 to auto-discover them) so the run fails loudly if a module
// ever stops shipping its rules — that is the regression this module guards.
val shippedConsumerRulePaths: List<String> =
    listOf(
        "META-INF/proguard/sdk-core.pro",
        "META-INF/proguard/sdk-io-okio3.pro",
        "META-INF/proguard/sdk-transport-okhttp.pro",
        "META-INF/proguard/sdk-transport-jdkhttp.pro",
        "META-INF/proguard/sdk-serde-jackson.pro",
    )

// Bundle the consumer program and its entire runtime classpath into one jar — the program R8 will
// shrink. `DuplicatesStrategy.EXCLUDE` keeps only the first occurrence of any duplicated path and
// skips the rest; it does not merge. That includes service-loader manifests — colliding
// `META-INF/services/<iface>` entries are NOT concatenated, only the first wins. It happens not to
// matter for the current classpath (no colliding service files), but a future dependency that
// ships one would need explicit concatenation (e.g. a Shadow `ServiceFileTransformer`) here.
val buildShrinkInputJar by tasks.registering(Jar::class) {
    group = "shrink"
    description = "Bundles the consumer program and its runtime classpath into the R8 input jar."

    dependsOn(tasks.named("classes"))
    destinationDirectory.set(shrinkBuildDir)
    archiveFileName.set("consumer-all.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val runtimeClasspath = configurations.named("runtimeClasspath")
    from(sourceSets.main.get().output)
    from(runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })

    // Drop signed-jar metadata that would otherwise make the merged jar fail verification, and
    // module descriptors that are meaningless once everything is flattened onto the classpath.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")

    manifest {
        attributes("Main-Class" to "org.dexpace.sdk.shrinktest.ShrinkSurvivalApp")
    }
}

// Java toolchain used to RUN R8 (the tool is Java-11 bytecode) and as R8's `--lib` runtime image.
// Java 8 program bytecode runs fine against an 11 boot image, so this does not change the program's
// target. Resolved lazily so configuration does not force toolchain provisioning.
val r8Launcher =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(11))
    }

val r8Shrink by tasks.registering(JavaExec::class) {
    group = "shrink"
    description = "Runs R8 in full mode over the consumer jar using the SDK's shipped keep-rules."

    dependsOn(buildShrinkInputJar)
    inputs.files(r8Tool)
    inputs.file(shrinkInputJar)
    inputs.file(layout.projectDirectory.file("src/r8/app-rules.pro"))
    outputs.file(shrunkJar)

    classpath = r8Tool
    mainClass.set("com.android.tools.r8.R8")
    javaLauncher.set(r8Launcher)

    doFirst {
        val inputJar = shrinkInputJar.get().asFile
        val outputJar = shrunkJar.get().asFile
        outputJar.parentFile.mkdirs()
        outputJar.delete()

        // Extract each shipped consumer-rules file out of the input jar and assert it is present.
        // A missing file here is exactly the shrink-survival regression we want to catch.
        val extractedRulesDir = shrinkBuildDir.get().dir("shipped-rules").asFile
        extractedRulesDir.mkdirs()
        val extractedRuleFiles = mutableListOf<File>()
        ZipFile(inputJar).use { zip ->
            shippedConsumerRulePaths.forEach { entryPath ->
                val entry =
                    zip.getEntry(entryPath)
                        ?: error(
                            "Shipped consumer keep-rules missing from the SDK on the classpath: " +
                                "$entryPath. Every SDK module that has reflectively/SPI-reached " +
                                "surface must ship its rules under META-INF/proguard.",
                        )
                val dest = File(extractedRulesDir, entryPath.substringAfterLast('/'))
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                extractedRuleFiles += dest
            }
        }

        // R8 needs a boot image to resolve java.* references; the launcher's JDK home serves as the
        // `--lib` runtime image.
        val jdkHome = r8Launcher.get().metadata.installationPath.asFile.absolutePath
        val appRules = layout.projectDirectory.file("src/r8/app-rules.pro").asFile

        val r8Args = mutableListOf("--release", "--classfile", "--output", outputJar.absolutePath)
        r8Args += listOf("--lib", jdkHome)
        extractedRuleFiles.forEach { r8Args += listOf("--pg-conf", it.absolutePath) }
        r8Args += listOf("--pg-conf", appRules.absolutePath)
        r8Args += inputJar.absolutePath
        args = r8Args

        logger.lifecycle(
            "Running R8 over ${inputJar.name} with ${extractedRuleFiles.size} shipped rule " +
                "file(s) + app rules",
        )
    }
}

val r8Run by tasks.registering(JavaExec::class) {
    group = "shrink"
    description = "Runs the R8-shrunk consumer program and asserts the SDK survived shrinking."

    dependsOn(r8Shrink)
    inputs.file(shrunkJar)
    // A marker output so the task is up-to-date when nothing changed.
    val resultMarker = shrinkBuildDir.map { it.file("r8-run-ok.txt") }
    outputs.file(resultMarker)

    // Run the shrunk classfiles directly; an 11 launcher executes the Java-8 program fine.
    javaLauncher.set(r8Launcher)
    mainClass.set("org.dexpace.sdk.shrinktest.ShrinkSurvivalApp")
    // `files(...)` is lazy, so referencing the not-yet-produced shrunk jar at configuration time is
    // fine — it is resolved when the task runs, after r8Shrink has created it.
    classpath = files(shrunkJar)

    val captured = ByteArrayOutputStream()
    standardOutput = captured
    // Capture rather than inherit stderr too, so a stack trace from a failing shrunk run is folded
    // into the diagnostic below instead of racing the captured stdout to the console.
    errorOutput = captured
    // Do not let a non-zero exit abort the task before doLast: if the shrunk program throws (the
    // SDK did not survive shrinking), JavaExec's default behaviour would fail here and discard the
    // captured output — exactly the diagnostic we need. We assert success ourselves in doLast.
    isIgnoreExitValue = true
    // `executionResult` is a member of JavaExec, but inside `doLast` the lambda receiver is the bare
    // Task; capture the provider here so the exit code is reachable when the action runs.
    val execResult = executionResult

    doLast {
        val output = captured.toString("UTF-8")
        logger.lifecycle(output.trim())
        val exitValue = execResult.get().exitValue
        check(exitValue == 0 && output.contains(successSentinel)) {
            "The R8-shrunk consumer did not survive shrinking with its shipped keep-rules " +
                "(exit code $exitValue). The SDK surface a downstream R8 build relies on was " +
                "stripped or broken. Program output was:\n$output"
        }
        resultMarker.get().asFile.writeText("ok\n")
    }
}

// Make the shrink-survival check part of the normal build: `./gradlew build` (which runs `check`)
// now bundles, shrinks, and runs the consumer, failing if the SDK does not survive R8.
tasks.named("check") {
    dependsOn(r8Run)
}
