# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Consumer ProGuard/R8 keep rules for sdk-serde-jackson.
#
# JacksonSerde is the public entry point (withDefaults() / from(ObjectMapper)). The module also
# registers a custom module that teaches Jackson how to (de)serialize Tristate; both the entry
# point and that module are reached reflectively through Jackson's module-registration and
# bean-introspection machinery, so they must survive shrinking.
-keep class org.dexpace.sdk.serde.jackson.JacksonSerde { *; }
-keep class org.dexpace.sdk.serde.jackson.JacksonObjectMappers { *; }
-keep class org.dexpace.sdk.serde.jackson.TristateModule { *; }

# Jackson databind is reflection-heavy: it reads annotations, walks bean members, and resolves
# parametric types at runtime, and its own config classes initialise from annotation enum
# singletons (a stripped or renamed enum value surfaces as an NPE in SerializationConfig's static
# initialiser). It is not meaningfully shrinkable without a hand-curated configuration, so the
# conventional — and the only safe — consumer recommendation is to keep the databind, core, and
# annotation packages wholesale, retain the attributes Jackson reflects over, and keep every
# annotation enum intact.
#
# Scope note: because this file ships under META-INF/proguard, R8/AGP applies it to the consumer's
# entire program, not just the SDK's classes. The wholesale Jackson `-keep` rules below therefore
# exempt the consumer's *entire* Jackson surface from shrinking — including any Jackson the app uses
# directly, elsewhere. The `-keepattributes` directive is likewise global: it adds these attributes
# to the whole consumer build, not only to SDK classes. An app that wants tighter shrinking can
# override these rules in its own configuration.
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.core.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep enum com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
