/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

/**
 * Per-call override for response-status validation (the policy that turns a non-success
 * status into a thrown error rather than a returned [org.dexpace.sdk.core.http.response.Response]).
 *
 * Modeled as a three-state enum rather than a nullable `Boolean` so the "leave the client
 * default in place" case is a first-class, non-`null` value — callers never reach for `!!`
 * to read it. [INHERIT] is the identity element of [CallOptions.applyDefaults]: an option set
 * carrying [INHERIT] contributes nothing and lets the lower-priority value through.
 */
public enum class ResponseValidation {
    /** Defer to the inherited client-level validation policy. The default for a fresh option set. */
    INHERIT,

    /** Force response validation on for this call, regardless of the client default. */
    ENABLED,

    /** Suppress response validation for this call, regardless of the client default. */
    DISABLED,
    ;

    /**
     * Overlays this value onto [default]: returns [default] when this is [INHERIT], otherwise
     * this explicit choice. Mirrors the per-field null-coalescing the rest of [CallOptions] uses.
     */
    public fun applyDefault(default: ResponseValidation): ResponseValidation = if (this == INHERIT) default else this
}
