/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

// Public API surface — not every HTTP method entry is referenced within this module; SDK consumers may use any.

/**
 * HTTP request methods recognized by the SDK. Each constant's name is the canonical token used
 * on the wire, so the enum's default `toString()` returns that token and the constant can be
 * written directly into a request line without translation.
 *
 * @property permitsRequestBody Whether this SDK permits the method to carry a request body.
 *   `false` for `GET`, `HEAD`, `TRACE`, and `CONNECT`. Of these only `TRACE` is forbidden a body
 *   outright by HTTP — a TRACE request "MUST NOT send content" (RFC 9110 §9.3.8); for `GET`/`HEAD`
 *   (§9.3.1/§9.3.2) and `CONNECT` (§9.3.6) a request payload has no generally defined semantics
 *   and is discouraged rather than illegal. The SDK rejects a body on all four as one consistent
 *   policy, because the reference transports otherwise diverge on the case — OkHttp throws
 *   `IllegalArgumentException`, the JDK builder silently ignores the body — so it is rejected once
 *   at request construction (see `Request.RequestBuilder.build`) rather than left to behave
 *   differently per transport.
 */
@Suppress("unused")
public enum class Method(public val permitsRequestBody: Boolean) {
    GET(permitsRequestBody = false),
    POST(permitsRequestBody = true),
    PUT(permitsRequestBody = true),
    DELETE(permitsRequestBody = true),
    PATCH(permitsRequestBody = true),
    HEAD(permitsRequestBody = false),
    OPTIONS(permitsRequestBody = true),
    TRACE(permitsRequestBody = false),
    CONNECT(permitsRequestBody = false),
    ;

    /**
     * Canonical uppercase method token sent in the request line; identical to the enum [name].
     *
     * `MemberNameEqualsClassName` is suppressed: the accessor is retained for API compatibility
     * (`getMethod()`) and the token genuinely *is* the method's name, so renaming it would only
     * obscure that and break callers.
     */
    @Suppress("MemberNameEqualsClassName")
    public val method: String get() = name
}
