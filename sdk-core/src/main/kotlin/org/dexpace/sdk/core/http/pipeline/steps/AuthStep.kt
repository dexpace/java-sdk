/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

/**
 * Pillar step at [Stage.AUTH]. Stamps credentials onto outgoing requests and exposes a
 * hook for handling 401 + `WWW-Authenticate` challenges.
 *
 * ## HTTPS-only
 *
 * The HTTPS requirement guards credential **stamping**: before a credential is attached
 * [process] rejects non-HTTPS schemes, before any token fetch or header stamp, to prevent
 * credential leakage over plaintext. The check is case-insensitive (`HTTPS`/`https`/`HtTpS`
 * all pass). Failure throws [IllegalStateException] naming the concrete step type and the
 * offending scheme. The guard is *not* applied when no credential is being attached — see
 * Cross-origin redirects, where a marker-suppressed re-issue is forwarded credential-free
 * over any scheme.
 *
 * ## Challenge retry
 *
 * On a 401 response carrying a `WWW-Authenticate` header, [authorizeRequestOnChallenge]
 * is consulted. If it returns a non-null replacement request, the original 401 response
 * is closed and the replacement is driven through the downstream chain exactly once
 * (no further challenge handling — one retry only). The default implementation returns
 * `null`; subclasses override to implement token-refresh / step-up auth flows.
 *
 * ## Cross-origin redirects
 *
 * The AUTH stage runs *inside* the REDIRECT stage, so a redirect re-issue flows back
 * through [process]. When [DefaultRedirectStep] follows a **cross-origin** redirect it
 * marks the re-issued request (see [CrossOriginRedirectMarker]); this step then skips
 * credential stamping so the caller's bearer/key credential is never re-applied to a
 * server-chosen foreign host. The marker is stripped here so it never reaches the wire.
 * Because no credential is attached on the marker-suppressed path, the HTTPS guard does
 * not apply: a marker-suppressed cross-origin re-issue is forwarded credential-free over
 * any scheme, so a redirect the redirect step deliberately allowed (including an opted-in
 * HTTPS→HTTP downgrade hop) is not turned into a hard failure. A same-origin redirect is
 * *not* marked and is re-stamped as normal — and is therefore still subject to the HTTPS
 * guard.
 *
 * ## Thread-safety
 *
 * The stage is locked at the type level via `final override`. Concrete subclasses must
 * themselves be safe for concurrent invocation — see [KeyCredentialAuthStep] (stateless)
 * and [BearerTokenAuthStep] (lock-guarded token cache).
 */
public abstract class AuthStep : HttpStep {
    final override val stage: Stage = Stage.AUTH

    @Throws(IOException::class)
    final override fun process(
        request: Request,
        next: PipelineNext,
    ): Response {
        // A cross-origin redirect re-issue is marked by DefaultRedirectStep; do NOT re-stamp
        // the caller's credential onto a server-chosen foreign host. The marker is stripped so
        // it never reaches the wire. This branch is evaluated BEFORE the HTTPS guard: no
        // credential is attached here, so the plaintext credential-leak the guard protects
        // against cannot occur, and a cross-origin hop the redirect step deliberately followed
        // (including an opted-in HTTPS->HTTP downgrade) must not be turned into a hard failure.
        // The HTTPS guard applies only on the credential-stamping branch below.
        val authorized =
            if (CrossOriginRedirectMarker.isMarked(request)) {
                request.newBuilder()
                    .headers(CrossOriginRedirectMarker.strip(request.headers))
                    .build()
            } else {
                val scheme = request.url.protocol
                check("https".equals(scheme, ignoreCase = true)) {
                    "${this::class.simpleName} requires HTTPS to prevent credential leak " +
                        "(URL scheme: $scheme)"
                }
                authorizeRequest(request)
            }
        val response = next.copy().process(authorized)

        if (response.status.code != SC_UNAUTHORIZED) return response
        response.headers.get(HttpHeaderName.WWW_AUTHENTICATE) ?: return response

        // Per HttpStep contract, re-driving the chain requires next.copy() — using `next`
        // directly would resume past steps already visited on the first attempt. The 401
        // body is closed before the retry; if authorizeRequestOnChallenge itself throws,
        // close the body before propagating so the caller never leaks the open response.
        val retryRequest =
            try {
                authorizeRequestOnChallenge(authorized, response)
            } catch (t: Throwable) {
                response.close()
                throw t
            } ?: return response
        response.close()
        return next.copy().process(retryRequest)
    }

    /**
     * Returns [request] with the credential's auth header attached. Subclasses implement
     * the concrete stamping (e.g. `Authorization: Bearer <token>`).
     *
     * Called once per request before the downstream chain is invoked.
     */
    @Throws(IOException::class)
    protected abstract fun authorizeRequest(request: Request): Request

    /**
     * Hook invoked on a 401 response that carries a `WWW-Authenticate` header. Default:
     * do not retry — return null to surface the 401 as the operation result.
     *
     * Subclasses override to refresh tokens, parse the `WWW-Authenticate` challenge, or
     * step up to a different auth scheme. Returning a non-null [Request] triggers a
     * single retry through the downstream chain; the original 401 is closed first.
     *
     * @param request the request already stamped with the original credential (the one
     *   that produced the 401). May or may not be reused for the retry.
     * @param response the 401 response; its body is still open at this point.
     */
    @Throws(IOException::class)
    protected open fun authorizeRequestOnChallenge(
        request: Request,
        response: Response,
    ): Request? = null

    private companion object {
        // HTTP 401 — only status code AuthStep responds to; on any other status the response
        // is returned unchanged so downstream steps can react.
        private const val SC_UNAUTHORIZED = 401
    }
}
