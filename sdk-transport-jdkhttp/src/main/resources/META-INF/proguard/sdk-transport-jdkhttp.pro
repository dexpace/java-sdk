# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Consumer ProGuard/R8 keep rules for sdk-transport-jdkhttp.
#
# JdkHttpTransport is the public entry point; callers construct it through builder() / create()
# and then use it only through the HttpClient / AsyncHttpClient interfaces. Keep the class, its
# Builder, and the static factories so the construction path survives shrinking. This mirrors the
# rules sdk-transport-okhttp ships for its own transport — every reference transport protects the
# same construction surface.
-keep class org.dexpace.sdk.transport.jdkhttp.JdkHttpTransport { *; }
-keep class org.dexpace.sdk.transport.jdkhttp.JdkHttpTransport$Builder { *; }
