package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.auth.BearerToken
import org.dexpace.sdk.core.http.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Request
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
 * ## Errors from [provider]
 *
 *  - Throws → propagated. The exception is **not** cached, so a subsequent request
 *    retries the fetch.
 *  - Returns null → wrapped in [IllegalStateException] (the provider is misbehaving).
 *  - Returns an already-expired token → wrapped in [IllegalStateException].
 *
 * ## Open for subclassing
 *
 * Users wanting to override [authorizeRequestOnChallenge] (e.g. force a token refresh on
 * 401) can extend this class.
 *
 * Thread-safety: see Caching above.
 * Cancellation: token fetch may block; the [provider] is expected to respect interrupts.
 */
public open class BearerTokenAuthStep @JvmOverloads constructor(
    private val provider: BearerTokenProvider,
    private val scopes: List<String>,
    private val refreshMargin: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.SYSTEM,
) : AuthStep() {
    private val lock = ReentrantLock()

    @Volatile
    private var cachedToken: BearerToken? = null

    override fun authorizeRequest(request: Request): Request {
        val token = currentToken()
        return request.newBuilder()
            .setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, "Bearer ${token.token}")
            .build()
    }

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
}
