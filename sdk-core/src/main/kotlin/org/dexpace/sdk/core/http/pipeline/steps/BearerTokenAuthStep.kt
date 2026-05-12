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
 * The pre-formatted header value (`"Bearer <token>"`) is also cached on the same fast
 * path so we don't concatenate on every request.
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
open class BearerTokenAuthStep @JvmOverloads constructor(
    private val provider: BearerTokenProvider,
    private val scopes: List<String>,
    private val refreshMargin: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.SYSTEM,
) : AuthStep() {
    private val lock = ReentrantLock()

    @Volatile
    private var cachedToken: BearerToken? = null

    @Volatile
    private var cachedHeaderValue: String? = null

    override fun authorizeRequest(request: Request): Request {
        val token = currentToken()
        // Reuse the pre-formatted header value when the cached token matched the read above;
        // a concurrent refresh between the two volatile reads is possible but harmless — the
        // worst case is one extra string concatenation, never an incorrect header.
        val header = cachedHeaderValue ?: "Bearer ${token.token}".also { cachedHeaderValue = it }
        return request.newBuilder()
            .setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, header)
            .build()
    }

    private fun currentToken(): BearerToken {
        val now = clock.now()
        cachedToken?.takeIf { !it.isExpiredAt(now, refreshMargin) }?.let { return it }
        lock.withLock {
            // Re-read inside the lock; another thread may have refreshed between our first
            // check and lock acquisition. `clock.now()` is re-sampled so the second check
            // uses the latest time.
            val nowInsideLock = clock.now()
            cachedToken?.takeIf { !it.isExpiredAt(nowInsideLock, refreshMargin) }?.let { return it }
            val fresh = fetchFresh()
            if (fresh.isExpiredAt(nowInsideLock)) {
                throw IllegalStateException(
                    "BearerTokenProvider returned an already-expired token"
                )
            }
            cachedToken = fresh
            // Invalidate the cached header so the next authorizeRequest re-computes it for
            // the new token.
            cachedHeaderValue = null
            return fresh
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
    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION", "UNCHECKED_CAST", "RedundantNullableReturnType")
    private fun fetchFresh(): BearerToken {
        val fetched: BearerToken? = provider.fetch(scopes) as BearerToken?
        return fetched ?: throw IllegalStateException("BearerTokenProvider returned null")
    }
}
