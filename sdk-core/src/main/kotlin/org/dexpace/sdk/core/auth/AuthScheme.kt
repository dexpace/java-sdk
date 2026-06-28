/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

// Public API surface — not every auth-scheme entry is referenced within this module; SDK consumers may use any.

/**
 * HTTP authentication schemes recognised by the SDK.
 *
 * Used in [AuthRequirement] / [AuthDescriptor] to describe the schemes a per-operation request
 * accepts, and to mark requests that must skip auth ([NO_AUTH]).
 */
@Suppress("unused")
public enum class AuthScheme {
    /** OAuth 2.0 bearer tokens. */
    OAUTH2,

    /** Static API keys stamped into a configured header. */
    API_KEY,

    /** RFC 7617 HTTP Basic auth. */
    BASIC,

    /** RFC 7616 HTTP Digest auth. */
    DIGEST,

    /**
     * Explicit "skip auth" marker — listed in an [AuthDescriptor] to bypass an
     * otherwise-configured credential step (e.g. anonymous probes against an authenticated
     * service).
     */
    NO_AUTH,
}
