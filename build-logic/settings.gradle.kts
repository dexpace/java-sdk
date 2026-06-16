/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

// Standalone settings for the `build-logic` included build. This build compiles the
// repository's convention plugins (precompiled `*.gradle.kts` script plugins) so that the
// production modules can apply them by id instead of duplicating configuration.
//
// `build-logic` deliberately depends on nothing from the version catalog: its sole convention
// plugin wires the core `maven-publish` and `signing` plugins, which ship with Gradle itself
// and therefore need no version. Keeping the included build catalog-free avoids extra
// `dependencyResolutionManagement { versionCatalogs { ... } }` plumbing and the associated
// classpath coupling between the main build and its own build logic.

rootProject.name = "build-logic"
