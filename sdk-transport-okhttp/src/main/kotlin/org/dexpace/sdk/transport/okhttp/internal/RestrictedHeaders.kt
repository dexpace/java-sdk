/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import java.util.Locale

/**
 * Critical-path headers OkHttp recomputes from the body or connection. Manually setting
 * any of these on the SDK [org.dexpace.sdk.core.http.request.Request] would either cause
 * OkHttp to reject the request or silently produce a wire-format mismatch — so the
 * adapter silently drops them before handing the request to OkHttp, emitting a DEBUG log
 * naming each dropped header.
 *
 * The list is intentionally narrow: `Connection` is **not** dropped because OkHttp accepts
 * a `Connection: close` user hint, unlike `java.net.http.HttpClient` which rejects it on
 * some JDK builds.
 *
 * Names are stored lower-case to match [org.dexpace.sdk.core.http.common.Headers]'
 * case-insensitive canonical form (sanitized via `Locale.US.lowercase`).
 */
internal object RestrictedHeaders {
    private val NAMES: Set<String> =
        setOf(
            "content-length".lowercase(Locale.US),
            "host".lowercase(Locale.US),
            "transfer-encoding".lowercase(Locale.US),
        )

    /** Returns `true` when [name] (case-insensitive) is in the drop list. */
    fun isRestricted(name: String): Boolean = NAMES.contains(name.lowercase(Locale.US))
}
