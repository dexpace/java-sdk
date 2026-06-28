/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import java.util.concurrent.CompletableFuture

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

    /**
     * Asynchronous counterpart of [fetch], used by
     * [org.dexpace.sdk.core.http.pipeline.steps.AsyncBearerTokenAuthStep] so a token refresh
     * never blocks the request-dispatching thread.
     *
     * The default implementation invokes the blocking [fetch] **on the calling thread** and
     * wraps the outcome into an already-completed [CompletableFuture] (completing exceptionally
     * if [fetch] throws). That default is correct but not non-blocking: a provider that talks to
     * a remote token endpoint should override this method to dispatch the fetch off-thread —
     * e.g. submit to an [java.util.concurrent.Executor], or call an async OAuth client — so the
     * returned future completes without parking the caller.
     *
     * Per-cloud providers (GCP / Azure / Kubernetes workload identity) and OAuth
     * token-exchange flows belong in adapter modules, not in `sdk-core`; this seam is what they
     * override.
     *
     * @param scopes OAuth scopes to request; service-specific.
     * @param params extra parameters to pass through to the token endpoint.
     * @return a future that completes with a fresh [BearerToken], or completes exceptionally
     *   with whatever [fetch] threw.
     */
    public fun fetchAsync(
        scopes: List<String>,
        params: Map<String, Any>,
    ): CompletableFuture<BearerToken> =
        try {
            CompletableFuture.completedFuture(fetch(scopes, params))
        } catch (t: Throwable) {
            // A provider's blocking fetch may throw any Throwable. Surface it through the
            // future rather than synchronously so async callers observe a uniform error model.
            // Error subclasses (OOM, StackOverflow) are intentionally NOT special-cased here:
            // the default just mirrors fetch()'s outcome into the future, and an Error in a
            // user-supplied lambda is still that lambda's failure, not a JVM-fatal one for us.
            val failed = CompletableFuture<BearerToken>()
            failed.completeExceptionally(t)
            failed
        }

    /** Convenience for callers without extra params; forwards to [fetchAsync] with an empty map. */
    public fun fetchAsync(scopes: List<String>): CompletableFuture<BearerToken> = fetchAsync(scopes, emptyMap())
}
