/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import java.util.Locale

/**
 * Critical-path headers the `java.net.http.HttpClient` recomputes from the body or connection.
 * Manually setting any of these on the SDK [org.dexpace.sdk.core.http.request.Request] would
 * either cause `java.net.http` to reject the request outright (via `IllegalArgumentException`
 * from `HttpRequest.Builder.header`) or silently produce a wire-format mismatch — so the
 * adapter silently drops them before handing the request to the JDK client, emitting a DEBUG
 * log naming each dropped header.
 *
 * The JDK client maintains an internal `DISALLOWED_HEADERS_SET` of header names that throw
 * `IllegalArgumentException` when set. Empirically (JDK 11 and JDK 21) that set is
 * `{connection, content-length, expect, host, upgrade}`. We pre-drop these so a user-set value
 * never reaches `HttpRequest.Builder.header(...)`:
 *
 *  - `Connection` — JDK 11+ rejects manual values (see `jdk.httpclient.allowRestrictedHeaders`
 *    if a consumer truly needs to override this, but the SDK does not).
 *  - `Content-Length` — recomputed from the body publisher's `contentLength()`.
 *  - `Expect` — the JDK drives `100-continue` itself; a manual `Expect: 100-continue` is rejected.
 *  - `Host` — always populated from the request URL.
 *  - `Upgrade` — protocol-upgrade negotiation is owned by the client, not the caller.
 *
 * `Transfer-Encoding` is also dropped defensively: although it is not in the JDK's reject set,
 * the JDK computes framing from streaming semantics, so a user-supplied value would only ever
 * conflict with the wire format the client emits.
 *
 * [RequestAdapter] additionally wraps the actual `builder.header(...)` call in a try/catch so
 * that any header outside this list which the JDK still rejects is dropped-with-DEBUG rather
 * than escaping `execute()` (declared `@Throws(IOException)`) as an `IllegalArgumentException`.
 *
 * Names are stored lower-case to match [org.dexpace.sdk.core.http.common.Headers]'
 * case-insensitive canonical form (sanitized via `Locale.US.lowercase`).
 */
internal object RestrictedHeaders {
    private val NAMES: Set<String> =
        setOf(
            "connection",
            "content-length",
            "expect",
            "host",
            "transfer-encoding",
            "upgrade",
        )

    /** Returns `true` when [name] (case-insensitive) is in the drop list. */
    fun isRestricted(name: String): Boolean = NAMES.contains(name.lowercase(Locale.US))
}
