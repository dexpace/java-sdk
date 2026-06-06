/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

/**
 * Source of fresh [BearerToken]s for [org.dexpace.sdk.core.http.pipeline.steps.BearerTokenAuthStep].
 *
 * Implementations typically wrap an OAuth token endpoint and cache + refresh the token
 * internally. The step itself maintains a top-level cache (so the provider is only called
 * when its cached token is missing or about to expire), but a provider that fetches from
 * a remote service should still cache aggressively because the step's cache is per-step,
 * not process-wide.
 *
 * Blocking: implementations may block to fetch a token (typical of OAuth flows). The
 * calling pipeline step does so on the request-processing thread; callers running on a
 * thread that must not block should arrange for the cache to be warmed elsewhere.
 *
 * Errors: throwing from [fetch] propagates to the caller; the step does not cache the
 * exception, so a subsequent request retries the fetch.
 *
 * Declared `fun interface` so callers can pass a lambda. The single abstract method takes
 * both `scopes` and `params`; a default-parameter overload on a `fun interface` SAM is
 * disallowed by Kotlin, so the convenience overload that accepts only `scopes` is a
 * non-abstract `fun` defined alongside the SAM. Java callers see only the two-argument
 * `fun fetch(...)` method.
 */
public fun interface BearerTokenProvider {
    /**
     * Fetches a fresh access token. Implementations should cache + refresh internally.
     *
     * @param scopes OAuth scopes to request; service-specific.
     * @param params extra parameters to pass through to the token endpoint (e.g.
     *   `claims`, `resource`).
     */
    public fun fetch(
        scopes: List<String>,
        params: Map<String, Any>,
    ): BearerToken

    /** Convenience for callers without extra params; forwards to [fetch] with an empty map. */
    public fun fetch(scopes: List<String>): BearerToken = fetch(scopes, emptyMap())
}
