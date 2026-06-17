/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

// Public API surface — not every HTTP method entry is referenced within this module; SDK consumers may use any.

/**
 * HTTP request methods recognized by the SDK. Each constant carries the canonical token used
 * on the wire; [toString] returns that same token so the enum can be written directly into a
 * request line without translation.
 *
 * @property method Canonical uppercase method token sent in the request line.
 * @property permitsRequestBody Whether HTTP allows this method to carry a request body. `false`
 *   for `GET`, `HEAD`, and `TRACE`: GET/HEAD have no defined payload semantics (RFC 9110
 *   §9.3.1/§9.3.2) and a TRACE request "MUST NOT send content" (§9.3.8). Transports disagree on
 *   how to handle a body on these methods — OkHttp throws `IllegalArgumentException`, the JDK
 *   builder silently ignores it — so a body on a body-forbidden method is rejected at request
 *   construction (see `Request.RequestBuilder.build`) rather than left to diverge per transport.
 */
@Suppress("unused")
public enum class Method(
    public val method: String,
    public val permitsRequestBody: Boolean,
) {
    GET("GET", permitsRequestBody = false),
    POST("POST", permitsRequestBody = true),
    PUT("PUT", permitsRequestBody = true),
    DELETE("DELETE", permitsRequestBody = true),
    PATCH("PATCH", permitsRequestBody = true),
    HEAD("HEAD", permitsRequestBody = false),
    OPTIONS("OPTIONS", permitsRequestBody = true),
    TRACE("TRACE", permitsRequestBody = false),
    CONNECT("CONNECT", permitsRequestBody = false),
    ;

    override fun toString(): String = method
}
