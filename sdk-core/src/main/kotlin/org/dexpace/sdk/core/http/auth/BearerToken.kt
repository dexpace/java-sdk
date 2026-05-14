package org.dexpace.sdk.core.http.auth

import java.time.Duration
import java.time.Instant

/**
 * OAuth-style access token + optional expiry.
 *
 * A null [expiresAt] models a long-lived token that never expires (a refresh would have to
 * be triggered manually by the application). When [expiresAt] is non-null, callers can
 * pre-emptively refresh by passing a `marginBefore` to [isExpiredAt] — useful for avoiding
 * mid-flight expiries during long requests.
 *
 * Value semantics via `data class` so equal tokens compare equal and can be diffed by
 * tests cheaply.
 *
 * @param token the bearer token string; must not be blank.
 * @param expiresAt the instant the token becomes invalid, or null for a non-expiring token.
 * @throws IllegalArgumentException if [token] is blank.
 */
public data class BearerToken(val token: String, val expiresAt: Instant?) : Credential {
    init { require(token.isNotBlank()) { "token must not be blank" } }

    /**
     * Returns true if this token is expired at [now] plus the optional [marginBefore]
     * grace window. A non-expiring token (one with `expiresAt == null`) is never expired.
     *
     * The grace window is *additive*: `isExpiredAt(now, 30s)` returns true 30 seconds
     * before the actual expiry — use it to pro-actively refresh.
     *
     * @param now the reference instant; usually `clock.now()`.
     * @param marginBefore extra time treated as "already expired"; defaults to zero.
     */
    @JvmOverloads
    public fun isExpiredAt(now: Instant, marginBefore: Duration = Duration.ZERO): Boolean =
        expiresAt != null && now.plus(marginBefore).isAfter(expiresAt)
}
