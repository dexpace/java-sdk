/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.request.Request
import java.net.URL

/**
 * Internal coordination point between the REDIRECT and AUTH pillar stages.
 *
 * The REDIRECT stage (order 100) wraps the AUTH stage (order 400): when [DefaultRedirectStep]
 * re-issues a request against a `Location` URL, that re-issued request flows back through the
 * AUTH step, which would otherwise unconditionally re-stamp the caller's credential. On a
 * **cross-origin** redirect that re-stamp would leak the bearer/key credential to a
 * server-chosen foreign host — exactly the leak the redirect step's `Authorization` strip is
 * meant to prevent.
 *
 * To close that gap without giving [Request] a generic attribute bag, the redirect step tags a
 * cross-origin re-issued request with a private sentinel header ([MARKER_HEADER]). The shipped
 * [AuthStep] base recognises the marker, skips credential stamping, and strips the marker before
 * the request continues downstream — so it never reaches the wire. A user-supplied `Location`
 * cannot forge the marker into a leak: the marker only *suppresses* stamping; it never *causes* a
 * credential to be sent.
 */
internal object CrossOriginRedirectMarker {
    /**
     * Private, SDK-internal request header set by [DefaultRedirectStep] on a cross-origin
     * re-issue and consumed (then removed) by [AuthStep]. The `X-Dexpace-Internal-` prefix and
     * the unconditional strip in [AuthStep.process] guarantee it never escapes to a transport.
     */
    const val MARKER_HEADER: String = "X-Dexpace-Internal-Cross-Origin-Redirect"

    /** Marker value; the presence of the header is what matters, the value is informational. */
    const val MARKER_VALUE: String = "1"

    /**
     * Returns `true` when [a] and [b] differ in scheme, host, or effective port (the RFC 6454
     * origin tuple). Host comparison is case-insensitive; the port is normalised to the
     * scheme's default when the URL omits it.
     */
    fun isCrossOrigin(
        a: URL,
        b: URL,
    ): Boolean {
        if (!a.protocol.equals(b.protocol, ignoreCase = true)) return true
        if (!hostsEqual(a.host, b.host)) return true
        return effectivePort(a) != effectivePort(b)
    }

    /** Returns `true` when [request] carries the cross-origin marker header. */
    fun isMarked(request: Request): Boolean = request.headers.contains(MARKER_HEADER)

    /** Returns a copy of [headers] with the marker header removed. */
    fun strip(headers: Headers): Headers = headers.newBuilder().remove(MARKER_HEADER).build()

    private fun hostsEqual(
        a: String?,
        b: String?,
    ): Boolean = (a ?: "").equals(b ?: "", ignoreCase = true)

    private fun effectivePort(url: URL): Int = if (url.port == -1) url.defaultPort else url.port
}
