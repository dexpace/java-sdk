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
 */
@Suppress("unused")
public enum class Method(
    public val method: String,
) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
    PATCH("PATCH"),
    HEAD("HEAD"),
    OPTIONS("OPTIONS"),
    TRACE("TRACE"),
    CONNECT("CONNECT"),
    ;

    override fun toString(): String = method
}
