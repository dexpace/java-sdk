# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Application-side R8 configuration for the shrink-survival harness.
#
# These rules stand in for what a real SDK consumer writes for THEIR OWN code; the rules that
# protect the SDK ship inside the SDK jars under META-INF/proguard and are picked up
# automatically (the harness verifies they are present and sufficient). Keeping them separate
# keeps this file honest: if a kept SDK member were missing from the shipped rules, the shrunk
# run would fail here rather than be papered over by an SDK-specific keep living app-side.

# Target a desktop/server JVM, not Android: emit classfiles, do not require a min API, and keep
# enough debug info that a stack trace from the shrunk run is legible.
#
# Obfuscation (member/class renaming) is deliberately OFF. This harness guards SHRINK survival:
# that R8's dead-code elimination does not strip the SDK's reflective and SPI surface. It does not
# guard obfuscation survival, because renaming would also rename the third-party libraries bundled
# here (OkHttp, Okio, Jackson), each of which ships its own consumer keep-rules that a real
# obfuscating consumer applies — reproducing that whole closure is out of scope for this module.
# The SDK's own shipped rules use `-keep ... { *; }`, which already pins names against renaming, so
# enabling obfuscation here would mostly test the bundled libraries' rules, not the SDK's.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The consumer's entry point. Everything the program needs is reachable from here, so this is
# the single application-side seed for the whole shrink.
-keep class org.dexpace.sdk.shrinktest.ShrinkSurvivalApp {
    public static void main(java.lang.String[]);
    public static java.lang.String SUCCESS_SENTINEL;
}

# The harness also reads the model class reflectively through Jackson; a real app would carry
# its own model keep, so we do too.
-keep class org.dexpace.sdk.shrinktest.ConsumerModel { *; }

# --- Suppress warnings for references R8 cannot resolve on a plain JVM classpath -------------
#
# These are optional/transitive integrations that the bundled libraries reference but never use
# in this program. A consumer's real build silences the same categories. None of them are SDK
# types; suppressing them does not weaken the survival assertion for the SDK surface.

# Kotlin stdlib's optional intrinsics and coroutine/annotation hooks.
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**

# SLF4J is compileOnly in the SDK; the program supplies no binding and logs nothing.
-dontwarn org.slf4j.**

# Okio / OkHttp / MockWebServer optional platform integrations and animal-sniffer annotations.
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn mockwebserver3.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**

# Jackson optional databind extensions (e.g. java.beans / DOM bridges) absent on this classpath.
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
