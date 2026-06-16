/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.auth.AuthChallengeParser
import org.dexpace.sdk.core.http.auth.BearerToken
import org.dexpace.sdk.core.http.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Clock
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [AuthStep] that stamps `Authorization: Bearer <token>` on outgoing requests, lazily
 * fetching the token from [provider] and caching it until [refreshMargin] before expiry.
 *
 * ## Caching
 *
 * The cached token is held in a `@Volatile` field; reads on the hot path are wait-free.
 * Refresh is guarded by a [ReentrantLock] with double-checked locking — only one thread
 * fetches across concurrent requests racing on an expiring token.
 *
 * The header string (`"Bearer <token>"`) is built inline on each request — concatenation
 * cost is negligible against the HTTP round trip it precedes, and skipping a separate
 * cache removes the lock/lock-free coordination problem that pairing the cache with the
 * token would introduce.
 *
 * ## Refresh margin
 *
 * [refreshMargin] is the grace window subtracted from the token's actual expiry instant
 * to decide when to refresh pre-emptively (default 30 seconds). A token whose `expiresAt`
 * is `now + 29s` with the default margin is considered expired and is refreshed.
 *
 * ## Eviction on 401
 *
 * The refresh margin only covers *local* expiry. A server may revoke a token before its
 * advertised expiry, and the cached token would otherwise keep being stamped until the
 * margin elapsed. To handle this, [authorizeRequestOnChallenge] evicts the rejected token
 * on a 401 + `WWW-Authenticate` response and re-stamps the request with a freshly fetched
 * one, so the single retry driven by [AuthStep] carries a new credential. Eviction is an
 * *additional* trigger layered on top of the margin check — it does not change pre-emptive
 * refresh. Only the token that produced the 401 is evicted; a token a concurrent request
 * already refreshed in the meantime is left in place. The retry runs regardless of HTTP
 * method (the in-step hook is not gated by retry-safety), so a non-idempotent POST is
 * re-authenticated too.
 *
 * Eviction only fires when the `WWW-Authenticate` header actually advertises a `Bearer`
 * challenge. A 401 carrying only a non-bearer challenge (e.g. `Basic`) — or an unparseable
 * one — is surfaced unchanged, because refreshing the bearer token would not satisfy it: the
 * retry would just be rejected again, at the cost of a wasted token fetch and round trip.
 *
 * A request that reaches the challenge hook carrying **no** `Authorization` header is a
 * cross-origin redirect re-issue whose credential the AUTH stage deliberately suppressed
 * (see [CrossOriginRedirectMarker]). In that case the hook re-stamps nothing and surfaces the
 * 401 unchanged, so the caller's token is never attached to a server-chosen foreign host.
 *
 * ## Errors from [provider]
 *
 *  - Throws → propagated. The exception is **not** cached, so a subsequent request
 *    retries the fetch.
 *  - Returns null → wrapped in [IllegalStateException] (the provider is misbehaving).
 *  - Returns an already-expired token → wrapped in [IllegalStateException].
 *
 * ## Open for subclassing
 *
 * Eviction-on-401 is built in. Users wanting to customise challenge handling further (e.g.
 * inspect the `WWW-Authenticate` scheme before deciding to refresh) can override
 * [authorizeRequestOnChallenge]. A subclass that changes the stamped header format by
 * overriding [authorizeRequest] must also override [bearerHeaderValue] so the eviction match
 * keeps recognising the rejected token.
 *
 * Thread-safety: see Caching above.
 * Cancellation: token fetch may block; the [provider] is expected to respect interrupts.
 */
public open class BearerTokenAuthStep
    @JvmOverloads
    constructor(
        private val provider: BearerTokenProvider,
        private val scopes: List<String>,
        private val refreshMargin: Duration = Duration.ofSeconds(DEFAULT_REFRESH_MARGIN_SECONDS),
        private val clock: Clock = Clock.SYSTEM,
    ) : AuthStep() {
        private val lock = ReentrantLock()

        @Volatile
        private var cachedToken: BearerToken? = null

        override fun authorizeRequest(request: Request): Request {
            val token = currentToken()
            return request.newBuilder()
                .setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, bearerHeaderValue(token.token))
                .build()
        }

        /**
         * On a 401 challenge, evict the token that was just rejected and re-stamp [request]
         * with a freshly fetched one so [AuthStep]'s single retry carries a new credential.
         *
         * Returns `null` — surfacing the 401 unchanged with no retry — in two cases:
         *
         *  - [request] carried no `Authorization` header. A request reaches this hook
         *    credential-free only when the AUTH stage deliberately suppressed stamping, i.e. a
         *    cross-origin redirect re-issue (see [CrossOriginRedirectMarker]). Re-stamping there
         *    would attach the caller's bearer token to a server-chosen foreign host and re-drive it
         *    through the chain, leaking the token cross-origin and bypassing the HTTPS guard that
         *    only [AuthStep.process]'s first pass enforces. Refusing to re-stamp preserves that
         *    suppression.
         *  - [response]'s `WWW-Authenticate` header does not advertise a `Bearer` challenge.
         *    Refreshing the bearer token cannot satisfy a `Basic`/`Digest`-only (or unparseable)
         *    challenge, so the retry would only earn a second 401 after a wasted fetch and round
         *    trip; the original 401 is surfaced instead.
         *
         * Otherwise eviction is scoped to the exact token that produced the 401 (matched against
         * the `Authorization` header [request] carried): if a concurrent request already refreshed
         * the cache, that newer token is preserved and reused for the retry rather than being
         * discarded. The subsequent [currentToken] call then re-fetches only when the cache is
         * actually empty, so a 401 storm across threads still funnels through the same
         * double-checked-locking fetch as the expiry-margin path.
         */
        override fun authorizeRequestOnChallenge(
            request: Request,
            response: Response,
        ): Request? {
            // No credential on the rejected request means stamping was suppressed (cross-origin
            // redirect). Do not re-attach one: surface the 401 unchanged.
            val rejectedHeader = request.headers.get(HttpHeaderName.AUTHORIZATION) ?: return null
            // A token refresh can only satisfy a Bearer challenge; surface anything else unchanged
            // rather than burning a fetch + retry that the server will just reject again.
            if (!offersBearerChallenge(response)) return null
            evictRejectedToken(rejectedHeader)
            return authorizeRequest(request)
        }

        /**
         * Returns `true` when [response]'s `WWW-Authenticate` header advertises a `Bearer`
         * challenge. A header with only non-bearer challenges (or one that does not parse) returns
         * `false`. [AuthStep] guarantees the header is present before this hook runs; the explicit
         * null-guard keeps the method correct if called from elsewhere.
         */
        private fun offersBearerChallenge(response: Response): Boolean {
            val header = response.headers.get(HttpHeaderName.WWW_AUTHENTICATE) ?: return false
            return AuthChallengeParser.parse(header).any { it.scheme == BEARER_SCHEME }
        }

        /**
         * Clears [cachedToken] iff it is still the token whose stamped header is [rejectedHeader].
         * Guarded by the same [lock] as the refresh path so the read-compare-clear is atomic
         * against a concurrent refresh. Routes the comparison through [bearerHeaderValue] so it
         * stays in lock-step with the value [authorizeRequest] stamps.
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
         * The `Authorization` header value for [token]. Single source of truth shared by
         * [authorizeRequest] (stamping) and [evictRejectedToken] (the eviction match), so the two
         * never drift. A subclass that overrides [authorizeRequest] to emit a different header
         * format must override this too, or eviction will stop matching and a rejected token will
         * be re-fetched but never cleared.
         */
        protected open fun bearerHeaderValue(token: String): String = "Bearer $token"

        private fun currentToken(): BearerToken {
            // Fast path: lock-free volatile read; return the cached token if it's still valid.
            cachedToken?.takeIf { !it.isExpiredAt(clock.now(), refreshMargin) }?.let { return it }

            return lock.withLock {
                // Re-read inside the lock; another thread may have refreshed between our first
                // check and lock acquisition. `clock.now()` is re-sampled so the second check
                // uses the latest time.
                val now = clock.now()
                cachedToken?.takeIf { !it.isExpiredAt(now, refreshMargin) }?.let { return@withLock it }
                val fresh = fetchFresh()
                // The fresh-token validation does NOT apply `refreshMargin`: a provider that just
                // minted a token returning expiration < margin is misbehaving (returning an
                // effectively-expired token), and the IllegalStateException must fire regardless of margin.
                check(!fresh.isExpiredAt(now)) {
                    "BearerTokenProvider returned an already-expired token"
                }
                cachedToken = fresh
                fresh
            }
        }

        /**
         * Calls [provider.fetch] and surfaces a `null` return as [IllegalStateException]. Note
         * that Kotlin inserts an intrinsic null check at the SAM call boundary when the Kotlin
         * compiler observes the declared non-nullable return type — for a Kotlin caller of a
         * Kotlin SAM, a null from a Java implementation is rejected as `NullPointerException`
         * **before** this method's own null check fires. The defensive check here remains for
         * platforms / contexts where intrinsics are disabled.
         */
        @Suppress(
            // `as BearerToken?` widens the non-nullable SAM return to nullable so the
            // compiler won't insert an implicit not-null assertion before our explicit
            // check; without the cast the compiler would throw NPE before we can surface
            // a friendlier IllegalStateException from the `?:` below.
            "UNCHECKED_CAST",
            // The local variable is intentionally typed as nullable (`BearerToken?`) even
            // though the declared SAM return is non-null, to prevent the compiler from
            // eliding the null check that follows.
            "RedundantNullableReturnType",
        )
        private fun fetchFresh(): BearerToken {
            val fetched: BearerToken? = provider.fetch(scopes) as BearerToken?
            return fetched ?: error("BearerTokenProvider returned null")
        }

        private companion object {
            // Default refresh margin: refresh the bearer token 30 seconds before its expiry
            // so an in-flight request never carries a near-expired credential.
            private const val DEFAULT_REFRESH_MARGIN_SECONDS = 30L

            // Lower-cased `Bearer` scheme name; AuthChallengeParser normalises schemes to lower
            // case, so the eviction gate compares against this constant.
            private const val BEARER_SCHEME = "bearer"
        }
    }
