# Copyright (c) 2026 dexpace and Omar Aljarrah
#
# Licensed under the MIT License. See LICENSE in the project root.
# SPDX-License-Identifier: MIT

# Consumer ProGuard/R8 keep rules for sdk-io-okio3.
#
# This module's only public surface is the OkioIoProvider singleton, installed at startup
# via Io.installProvider(OkioIoProvider). A shrinker following the application from its own
# entry points cannot always see that wiring, so keep the provider and its INSTANCE field.
-keep class org.dexpace.sdk.io.OkioIoProvider { *; }
