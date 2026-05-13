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
    ): Pair<String, String>? {
        if (!canHandle(challenges)) return null
        val headerName = if (isProxy) "Proxy-Authorization" else "Authorization"
        return headerName to authHeaderValue
    }
}
