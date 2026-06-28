/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.auth.BearerToken
import org.dexpace.sdk.core.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [AsyncAuthStep] that stamps `Authorization: Bearer <token>` on outgoing requests, fetching the
 * token from [provider] via [BearerTokenProvider.fetchAsync] so a refresh never blocks the
 * dispatching thread. The async counterpart of [BearerTokenAuthStep].
 *
 * ## Background refresh
 *
 * The cached token is held in a `@Volatile` field; the hot-path read is wait-free. The expiry
 * decision has three zones, keyed off [refreshMargin]:
 *
 *  - **Fresh** — `now + margin < expiresAt`: stamp the cached token, no refresh.
 *  - **Expiring** — `expiresAt - margin <= now < expiresAt`: the token is still *valid*, so it
 *    is returned **immediately** and stamped, while a refresh is kicked off **off-thread** in the
 *    background. The in-flight request never waits on the token endpoint; the next request picks
 *    up the refreshed token once it lands. This is the behaviour issue #32 asks for: a
 *    valid-but-expiring token returned without blocking.
 *  - **Expired / missing** — `now >= expiresAt`, or no cached token: there is no usable
 *    credential, so the request **must** await a fresh fetch before it can be stamped.
 *
 * ## Single-flight
 *
 * Concurrent requests that all observe an expiring or missing token share **one** in-flight
 * [provider] call rather than each hitting the token endpoint (no stampede). The in-flight future
 * is published under a [ReentrantLock]; whoever wins the lock starts the fetch, everyone else
 * joins the same future. On completion the in-flight slot is cleared so a later refresh starts a
 * new fetch. A failed fetch is **not** cached — the next request retries.
 *
 * ## Eviction on 401
 *
 * Like [BearerTokenAuthStep], a 401 + `WWW-Authenticate: Bearer` evicts the rejected token and
 * re-stamps the single [AsyncAuthStep] retry with a freshly fetched one. Eviction is scoped to
 * the exact token that produced the 401; a token a concurrent request already refreshed is left
 * in place. A 401 with no bearer challenge (or none at all), or a request that reached the hook
 * credential-free (cross-origin suppression), surfaces unchanged.
 *
 * ## Errors from [provider]
 *
 *  - Future completes exceptionally → propagated; not cached, so a later request retries.
 *  - Future completes with `null` → surfaced as [IllegalStateException].
 *  - Future completes with an already-expired token → surfaced as [IllegalStateException].
 *
 * A background (expiring-zone) refresh that fails or returns an unusable token does **not** fail
 * the in-flight request — the still-valid cached token was already stamped — it only logs and
 * leaves the cache for the next request to refresh.
 *
 * ## Open for subclassing
 *
 * Override [bearerHeaderValue] to change the header format (and keep eviction matching), or
 * [authorizeRequestOnChallengeAsync] to customise challenge handling.
 */
public open class AsyncBearerTokenAuthStep
    @JvmOverloads
    constructor(
        private val provider: BearerTokenProvider,
        private val scopes: List<String>,
        private val refreshMargin: Duration = Duration.ofSeconds(DEFAULT_REFRESH_MARGIN_SECONDS),
        private val clock: Clock = Clock.SYSTEM,
        private val logger: ClientLogger = ClientLogger(AsyncBearerTokenAuthStep::class),
    ) : AsyncAuthStep() {
        private val lock = ReentrantLock()

        @Volatile
        private var cachedToken: BearerToken? = null

        // The single shared in-flight fetch, or null when none is running. Published / cleared
        // under [lock] so concurrent expiring/missing requests coalesce onto one provider call.
        @Volatile
        private var inFlight: CompletableFuture<BearerToken>? = null

        override fun authorizeRequestAsync(request: Request): CompletableFuture<Request> =
            currentToken().thenApply { token -> stamp(request, token) }

        override fun authorizeRequestOnChallengeAsync(
            request: Request,
            response: Response,
        ): CompletableFuture<Request?> {
            // No credential on the rejected request → stamping was suppressed (cross-origin
            // redirect). Surface the 401 unchanged.
            val rejectedHeader =
                request.headers.get(HttpHeaderName.AUTHORIZATION)
                    ?: return CompletableFuture.completedFuture(null)
            // A token refresh can only satisfy a Bearer challenge.
            if (!offersBearerChallenge(response)) return CompletableFuture.completedFuture(null)
            evictRejectedToken(rejectedHeader)
            // forceFresh: the rejected token was just evicted; await a genuinely fresh fetch
            // before re-stamping so the retry never carries the same rejected credential.
            return forceFreshToken().thenApply { token -> stamp(request, token) as Request? }
        }

        private fun stamp(
            request: Request,
            token: BearerToken,
        ): Request =
            request.newBuilder()
                .setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, bearerHeaderValue(token.token))
                .build()

        /**
         * Resolves the token to stamp for the current request, applying the three-zone expiry
         * policy (fresh / expiring / expired). Never blocks: the returned future completes
         * immediately when a usable cached token exists (even if a background refresh is also
         * kicked off), and otherwise completes when the single-flight fetch lands.
         */
        private fun currentToken(): CompletableFuture<BearerToken> {
            val now = clock.now()
            val cached = cachedToken
            if (cached != null && !cached.isExpiredAt(now)) {
                // Token is still valid. If it is inside the refresh margin, return it now and
                // refresh in the background; otherwise just return it.
                if (cached.isExpiredAt(now, refreshMargin)) {
                    startBackgroundRefresh()
                }
                return CompletableFuture.completedFuture(cached)
            }
            // Missing or hard-expired: must await a fresh fetch.
            return forceFreshToken()
        }

        /**
         * Kicks off a single-flight refresh whose result the caller does NOT await. Used on the
         * expiring-but-valid path: the still-valid cached token is what the in-flight request
         * stamps; this just warms the cache for the next request.
         */
        private fun startBackgroundRefresh() {
            // Reuse the single-flight machinery; ignore the returned future (fire-and-forget). The
            // failure log is attached via [sharedFetch]'s onLaunch hook so ONLY the caller that
            // actually launches the fetch logs it: every request in the expiring window funnels onto
            // one in-flight refresh, and a single failed refresh must log once — not once per
            // concurrent joiner. (A forced, awaited fetch surfaces its failure to the caller and
            // passes no onLaunch, so it is never double-counted here.)
            sharedFetch(
                onLaunch = { fetch ->
                    fetch.whenComplete { _, error ->
                        if (error != null) {
                            logger.atWarning()
                                .event("http.auth.background_refresh_failed")
                                .field("error.type", error::class.java.simpleName ?: "Throwable")
                                .cause(error)
                                .log()
                        }
                    }
                },
            )
        }

        /**
         * Returns a future that completes with a usable token, awaiting the single-flight fetch.
         * Used when there is no usable cached token (missing / hard-expired) and on the
         * post-eviction challenge path.
         */
        private fun forceFreshToken(): CompletableFuture<BearerToken> = sharedFetch()

        /**
         * Single-flight fetch coordinator. The first caller to find no in-flight fetch starts one
         * (publishing it under [lock]); concurrent callers join the same future. The in-flight
         * slot is cleared on completion so a subsequent refresh starts fresh. A re-check of the
         * cache inside the lock means a token another thread just refreshed short-circuits the
         * fetch.
         *
         * [onLaunch] is invoked exactly once, and ONLY for the caller that actually starts a new
         * fetch — never for a cache hit or a joiner onto an already-in-flight fetch. It runs
         * outside [lock] (so a callback it attaches never executes under the lock), which is how
         * the background-refresh path attaches its failure log without it firing once per joiner.
         */
        private fun sharedFetch(
            onLaunch: ((CompletableFuture<BearerToken>) -> Unit)? = null,
        ): CompletableFuture<BearerToken> {
            val launched: CompletableFuture<BearerToken> =
                lock.withLock {
                    // Re-read inside the lock: another thread may have just refreshed.
                    val now = clock.now()
                    cachedToken?.takeIf { !it.isExpiredAt(now, refreshMargin) }
                        ?.let { return CompletableFuture.completedFuture(it) }
                    inFlight?.let { return it }
                    val fetch = launchFetch()
                    inFlight = fetch
                    // Attach cache bookkeeping AFTER publishing, so the clear-on-complete callback
                    // compares against the exact future stored in `inFlight`. Attaching here (rather
                    // than inside launchFetch) sidesteps the self-reference an inline-completed future
                    // would otherwise need.
                    fetch.whenComplete { token, error ->
                        lock.withLock {
                            if (inFlight === fetch) inFlight = null
                            if (error == null && token != null) cachedToken = token
                        }
                    }
                    fetch
                }
            onLaunch?.invoke(launched)
            return launched
        }

        /**
         * Starts the provider fetch and applies validation (null token, already-expired token) so
         * a misbehaving provider surfaces as [IllegalStateException] to the awaiting caller. Cache
         * population and in-flight clearing are attached by [sharedFetch] against the published
         * future.
         */
        private fun launchFetch(): CompletableFuture<BearerToken> {
            val raw: CompletableFuture<BearerToken> =
                try {
                    fetchAsyncSafe()
                } catch (t: Throwable) {
                    // A provider whose fetchAsync throws synchronously (caller-bug) — normalise.
                    val failed = CompletableFuture<BearerToken>()
                    failed.completeExceptionally(t)
                    failed
                }
            return raw.thenApply { token -> validateFresh(token) }
        }

        @Suppress(
            "UNCHECKED_CAST",
            "RedundantNullableReturnType",
        )
        private fun fetchAsyncSafe(): CompletableFuture<BearerToken> {
            val future: CompletableFuture<BearerToken>? =
                provider.fetchAsync(scopes) as CompletableFuture<BearerToken>?
            return future ?: error("BearerTokenProvider.fetchAsync returned null")
        }

        /**
         * Validates a freshly fetched token: rejects a `null` (a Java provider may hand one back
         * despite the non-null Kotlin signature when null-check intrinsics are disabled — hence the
         * nullable parameter) and a token already expired at fetch time (no margin applied — a
         * provider generating an effectively-expired token is misbehaving).
         */
        private fun validateFresh(token: BearerToken?): BearerToken {
            val nonNull = token ?: error("BearerTokenProvider returned null")
            check(!nonNull.isExpiredAt(clock.now())) {
                "BearerTokenProvider returned an already-expired token"
            }
            return nonNull
        }

        /**
         * Clears [cachedToken] iff it is still the token whose stamped header is [rejectedHeader].
         * Guarded by the same [lock] as the fetch path so the read-compare-clear is atomic against
         * a concurrent refresh.
         */
        private fun evictRejectedToken(rejectedHeader: String) {
            lock.withLock {
                val current = cachedToken ?: return
                if (bearerHeaderValue(current.token) == rejectedHeader) {
                    cachedToken = null
                }
            }
        }

        /**
         * The `Authorization` header value for [token]. Single source of truth shared by the
         * stamping path and [evictRejectedToken]. A subclass that emits a different header format
         * must override this too, or eviction stops matching.
         */
        protected open fun bearerHeaderValue(token: String): String = "Bearer $token"

        private companion object {
            private const val DEFAULT_REFRESH_MARGIN_SECONDS = 30L
        }
    }
