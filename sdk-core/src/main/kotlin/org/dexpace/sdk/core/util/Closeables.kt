/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

/**
 * Best-effort close that swallows any failure. Use on a discard path — where the value is already
 * being dropped (a computed result that lost a completion race, a response being released before a
 * retry), so a failure to close it has nothing actionable to surface. Null-safe.
 *
 * This is the shared silent-drop helper; call sites that need to *log* a dropped close error, or to
 * attach it as suppressed onto a primary throwable, deliberately keep their own local variant.
 */
internal fun closeQuietly(closeable: AutoCloseable?) {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (ignored: Throwable) {
        // Intentionally ignored: the value is already being discarded.
    }
}
