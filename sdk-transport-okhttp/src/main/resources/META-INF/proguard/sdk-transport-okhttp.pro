# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Consumer ProGuard/R8 keep rules for sdk-transport-okhttp.
#
# OkHttpTransport is the public entry point; callers construct it through builder() / create()
# and then use it only through the HttpClient / AsyncHttpClient interfaces. Keep the class, its
# Builder, and the static factories so the construction path survives shrinking.
-keep class org.dexpace.sdk.transport.okhttp.OkHttpTransport { *; }
-keep class org.dexpace.sdk.transport.okhttp.OkHttpTransport$Builder { *; }

# OkHttp performs its own service/reflection lookups (platform TLS, optional Conscrypt and
# BouncyCastle providers) that are absent on a plain JVM classpath. Suppress warnings for those
# optional integrations so a consumer's shrink does not fail on references it will never use.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
