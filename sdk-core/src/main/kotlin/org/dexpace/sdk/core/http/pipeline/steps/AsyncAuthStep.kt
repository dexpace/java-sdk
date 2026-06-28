/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture

/**
 * Async pillar step at [Stage.AUTH] — the [AsyncHttpStep] counterpart of [AuthStep]. Stamps
 * credentials onto outgoing requests via an async [authorizeRequestAsync] (so a token fetch /
 * refresh never blocks the dispatching thread) and exposes the same 401 + `WWW-Authenticate`
 * challenge hook.
 *
 * The stamping and challenge semantics mirror [AuthStep] exactly:
 *
 *  - **HTTPS-only.** On the path that attaches a credential, [processAsync] rejects non-HTTPS
 *    schemes before any token fetch. The guard is skipped on the marker-suppressed cross-origin
 *    re-issue path, where no credential is attached.
 *  - **Cross-origin redirects.** A re-issue marked by the redirect step (see
 *    [CrossOriginRedirectMarker]) is forwarded credential-free; the marker is stripped before
 *    the request reaches the wire.
 *  - **Challenge retry.** On a 401 carrying `WWW-Authenticate`, [authorizeRequestOnChallengeAsync]
 *    is consulted; a non-null replacement is driven through the chain exactly once (no further
 *    challenge handling). The default returns a future of `null` (no retry).
 *
 * Unlike the synchronous [AuthStep] the credential-attaching guard checks and the downstream
 * dispatches are composed on [CompletableFuture]s so the whole flow stays non-blocking.
 *
 * ## Thread-safety
 *
 * The stage is locked at the type level via `final override`. Concrete subclasses must be safe
 * for concurrent invocation — see [AsyncBearerTokenAuthStep] (single-flight token refresh).
 */
public abstract class AsyncAuthStep : AsyncHttpStep {
    final override val stage: Stage = Stage.AUTH

    final override fun processAsync(
        request: Request,
        next: AsyncPipelineNext,
    ): CompletableFuture<Response> {
        val authorizedFuture: CompletableFuture<Request> =
            if (CrossOriginRedirectMarker.isMarked(request)) {
                // Cross-origin redirect re-issue: strip the marker, attach no credential.
                CompletableFuture.completedFuture(
                    request.newBuilder()
                        .headers(CrossOriginRedirectMarker.strip(request.headers))
                        .build(),
                )
            } else {
                val scheme = request.url.protocol
                if (!"https".equals(scheme, ignoreCase = true)) {
                    Futures.failed(
                        IllegalStateException(
                            "${this::class.simpleName} requires HTTPS to prevent credential leak " +
                                "(URL scheme: $scheme)",
                        ),
                    )
                } else {
                    authorizeRequestAsync(request)
                }
            }

        return authorizedFuture.thenCompose { authorized ->
            next.copy().processAsync(authorized).thenCompose { response ->
                handleChallenge(authorized, response, next)
            }
        }
    }

    /**
     * After the first downstream attempt, applies the 401 + `WWW-Authenticate` challenge hook.
     * Returns the response unchanged unless [authorizeRequestOnChallengeAsync] yields a non-null
     * replacement, in which case the original 401 is closed and the replacement is driven once.
     */
    private fun handleChallenge(
        authorized: Request,
        response: Response,
        next: AsyncPipelineNext,
    ): CompletableFuture<Response> {
        if (response.status.code != SC_UNAUTHORIZED) return CompletableFuture.completedFuture(response)
        response.headers.get(HttpHeaderName.WWW_AUTHENTICATE)
            ?: return CompletableFuture.completedFuture(response)

        val challengeFuture: CompletableFuture<Request?> =
            try {
                authorizeRequestOnChallengeAsync(authorized, response)
            } catch (t: Throwable) {
                // A sync throw from the hook (caller-bug case) must still close the 401 body.
                response.close()
                return Futures.failed(t)
            }

        return challengeFuture.handle<CompletableFuture<Response>> { retryRequest, hookError ->
            if (hookError != null) {
                response.close()
                return@handle Futures.failed<Response>(Futures.unwrap(hookError))
            }
            if (retryRequest == null) return@handle CompletableFuture.completedFuture(response)
            response.close()
            next.copy().processAsync(retryRequest)
        }.thenCompose { it }
    }

    /**
     * Returns a future of [request] with the credential's auth header attached. Subclasses
     * implement the concrete async stamping (e.g. fetch-or-refresh a bearer token off-thread,
     * then stamp `Authorization: Bearer <token>`).
     *
     * Called once per request before the downstream chain is invoked.
     */
    protected abstract fun authorizeRequestAsync(request: Request): CompletableFuture<Request>

    /**
     * Hook invoked on a 401 response that carries a `WWW-Authenticate` header. The default
     * returns a future of `null` — surface the 401 with no retry.
     *
     * Subclasses override to refresh tokens or step up auth. A non-null [Request] in the
     * returned future triggers a single retry through the downstream chain; the original 401 is
     * closed first.
     *
     * @param request the request already stamped with the credential that produced the 401.
     * @param response the 401 response; its body is still open at this point.
     */
    protected open fun authorizeRequestOnChallengeAsync(
        request: Request,
        response: Response,
    ): CompletableFuture<Request?> = CompletableFuture.completedFuture(null)

    private companion object {
        // HTTP 401 — the only status code AsyncAuthStep responds to.
        private const val SC_UNAUTHORIZED = 401
    }
}
