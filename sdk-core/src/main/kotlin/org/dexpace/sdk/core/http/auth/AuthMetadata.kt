/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import java.util.Collections

/**
 * Per-request auth metadata describing which [AuthScheme]s an operation supports and any
 * OAuth-specific parameters (scopes / extra params) the auth step should forward to the
 * [BearerTokenProvider].
 *
 * **Not currently wired into [org.dexpace.sdk.core.http.pipeline.steps.AuthStep]'s lookup
 * path.** [org.dexpace.sdk.core.http.context.RequestContext] is presently a fixed-field
 * `data class` with no typed-key attachment mechanism, so there is no general way to
 * attach an `AuthMetadata` to a request and have the step retrieve it. Wiring this into
 * per-request lookup is deferred until the SDK adopts a typed-key context store on
 * `RequestContext`; the type is defined here so downstream code (operation generators,
 * documentation, future wiring) can reference it without a parallel refactor blocking
 * Rank 6.
 *
 * Each collection is defensively copied on the way in and exposed as an unmodifiable view,
 * so a caller that retains and later mutates the argument collection cannot mutate this
 * instance (or re-break the non-empty [schemes] invariant) after construction.
 *
 * @param schemes the auth schemes this operation supports, in preference order. Must not be empty.
 * @param oauthScopes OAuth scopes to forward to the token provider; ignored for non-OAuth schemes.
 * @param oauthParams extra OAuth params to forward (e.g. `claims`); defaults to empty.
 * @throws IllegalArgumentException if [schemes] is empty.
 */
public class AuthMetadata
    @JvmOverloads
    constructor(
        schemes: List<AuthScheme>,
        oauthScopes: List<String> = emptyList(),
        oauthParams: Map<String, Any> = emptyMap(),
    ) {
        /** The supported auth schemes, in preference order; an unmodifiable defensive copy. */
        public val schemes: List<AuthScheme> = Collections.unmodifiableList(schemes.toList())

        /** OAuth scopes to forward to the token provider; an unmodifiable defensive copy. */
        public val oauthScopes: List<String> = Collections.unmodifiableList(oauthScopes.toList())

        /** Extra OAuth params to forward; an unmodifiable defensive copy. */
        public val oauthParams: Map<String, Any> = Collections.unmodifiableMap(oauthParams.toMap())

        init {
            require(this.schemes.isNotEmpty()) { "schemes must not be empty" }
        }
    }
