package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI
import java.util.Base64

/**
 * RFC 7617 `Basic` authentication handler.
 *
 * Encodes the credential pair once at construction (`Basic base64(username:password)`)
 * and returns it verbatim on every matching challenge. No per-call allocation.
 *
 * Thread-safety: immutable after construction.
 *
 * Security: `Basic` transmits the password in a trivially reversible form. Use only
 * over HTTPS or trusted-network tunnels.
 */
class BasicChallengeHandler(username: String, password: String) : ChallengeHandler {

    private val authHeaderValue: String

    init {
        // isNotEmpty (not isNotBlank) is intentional: RFC 7617 (Basic auth) permits
        // whitespace-only credentials; the user-supplied string is transmitted as-is
        // inside the Base64-encoded `username:password` pair. DigestChallengeHandler uses
        // the stricter isNotBlank because RFC 7616 Digest credentials must be meaningful
        // tokens. The inconsistency between the two handlers is per-spec.
        require(username.isNotEmpty()) { "username must not be empty" }
        require(password.isNotEmpty()) { "password must not be empty" }
        val encoded = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        authHeaderValue = "Basic $encoded"
    }

    override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean =
        challenges.any { it.scheme.equals("Basic", ignoreCase = true) }

    override fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
        isProxy: Boolean,
    ): AuthorizationHeader? {
        if (!canHandle(challenges)) return null
        val headerName = if (isProxy) "Proxy-Authorization" else "Authorization"
        return AuthorizationHeader(headerName, authHeaderValue)
    }
}
