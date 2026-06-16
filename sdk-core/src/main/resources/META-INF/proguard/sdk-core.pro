# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Consumer ProGuard/R8 keep rules for sdk-core.
#
# R8 and the Android Gradle Plugin automatically apply any rules packaged under
# META-INF/proguard/ in a dependency jar, so a downstream application that shrinks its
# build inherits these without extra configuration. They protect the parts of the toolkit
# that a shrinker cannot prove are reachable on its own:
#
#   * the SPI seams that callers wire at runtime (the I/O provider, the transport clients,
#     the serde), whose implementations live in separate modules and are referenced only
#     through interfaces; and
#   * the immutable HTTP models and the Tristate type, which Jackson and other reflective
#     serializers bind by walking constructors, accessors, and Kotlin metadata rather than
#     through direct call sites the shrinker can see.

# --- SPI contracts wired at runtime --------------------------------------------------

# The single I/O seam. Io.installProvider(...) is the documented entry point and IoProvider
# is implemented in an adapter module, so keep both surfaces intact.
-keep class org.dexpace.sdk.core.io.Io { *; }
-keep class org.dexpace.sdk.core.io.IoProvider { *; }

# Transport SPIs. Concrete transports (e.g. OkHttpTransport) are reached only through these
# interfaces, so the methods a caller invokes must survive.
-keep class org.dexpace.sdk.core.client.HttpClient { *; }
-keep class org.dexpace.sdk.core.client.AsyncHttpClient { *; }

# Serde SPI. JacksonSerde and any other implementation are reached through these interfaces.
-keep class org.dexpace.sdk.core.serde.Serde { *; }
-keep class org.dexpace.sdk.core.serde.Serializer { *; }
-keep class org.dexpace.sdk.core.serde.Deserializer { *; }

# --- Immutable HTTP models and their builders ----------------------------------------

# Request / Response and their nested builders are constructed and read reflectively by
# serializers and assertion frameworks; preserving every member keeps the public surface
# (factories, builder fluents, component accessors) callable after shrinking.
-keep class org.dexpace.sdk.core.http.request.Request { *; }
-keep class org.dexpace.sdk.core.http.request.Request$RequestBuilder { *; }
-keep class org.dexpace.sdk.core.http.request.RequestBody { *; }
-keep class org.dexpace.sdk.core.http.request.Method { *; }
-keep class org.dexpace.sdk.core.http.response.Response { *; }
-keep class org.dexpace.sdk.core.http.response.Response$ResponseBuilder { *; }
-keep class org.dexpace.sdk.core.http.response.ResponseBody { *; }
-keep class org.dexpace.sdk.core.http.response.Status { *; }
-keep class org.dexpace.sdk.core.http.common.Headers { *; }
-keep class org.dexpace.sdk.core.http.common.Headers$Builder { *; }
-keep class org.dexpace.sdk.core.http.common.MediaType { *; }
-keep class org.dexpace.sdk.core.http.common.CommonMediaTypes { *; }
-keep class org.dexpace.sdk.core.http.common.Protocol { *; }

# --- Tristate ------------------------------------------------------------------------

# Tristate models the absent / null / present distinction a serializer must reconstruct from
# the wire. The custom Jackson binding (shipped by sdk-serde-jackson) checks the runtime type
# of each variant, so the sealed hierarchy and the Present payload accessor must remain.
-keep class org.dexpace.sdk.core.serde.Tristate { *; }
-keep class org.dexpace.sdk.core.serde.Tristate$Absent { *; }
-keep class org.dexpace.sdk.core.serde.Tristate$Null { *; }
-keep class org.dexpace.sdk.core.serde.Tristate$Present { *; }

# Kotlin emits @Metadata on every class; reflective Kotlin tooling (including Jackson's Kotlin
# module) reads it to recover constructor parameter names and nullability. Strip it and
# data-class binding silently degrades, so keep the annotation across the toolkit.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }
