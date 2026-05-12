package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI

/**
 * Strategy for handling `WWW-Authenticate` (or `Proxy-Authenticate`) challenges.
 *
 * A handler inspects the parsed challenge list and either produces the
 * (header-name, value) pair to set on the retry request, or returns `null` to
 * signal that it cannot satisfy any of the offered challenges.
 *
 * Multiple handlers can be composed via [of]. The composite consults handlers in
 * declaration order; the first one whose [canHandle] returns true wins. Order
 * matters — register `DigestChallengeHandler` before `BasicChallengeHandler` so
 * Digest is preferred when both are offered.
 *
 * Thread-safety: implementations are required to be safe under concurrent
 * invocation across requests. Stateful counters (e.g. Digest's `nc`) must be
 * thread-safe primitives.
 */
interface ChallengeHandler {

    /**
     * Returns the header to set on the retry request, or `null` if this handler
     * cannot satisfy any offered challenge. The `method` and `uri` are required
     * for schemes (like Digest) that sign the request line.
     *
     * The returned header name is `Authorization` for `WWW-Authenticate` challenges
     * and `Proxy-Authorization` for `Proxy-Authenticate`. Callers signal which via
     * [isProxy].
     */
    fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
        isProxy: Boolean = false,
    ): Pair<String, String>?

    /** True if any offered challenge is one this handler can satisfy. */
    fun canHandle(challenges: List<AuthenticateChallenge>): Boolean

    companion object {
        /**
         * Composes [handlers] into one. The composite delegates to the first
         * handler whose [canHandle] returns true. Order matters — register Digest
         * before Basic when both might apply.
         */
        @JvmStatic
        fun of(vararg handlers: ChallengeHandler): ChallengeHandler =
            CompositeChallengeHandler(handlers.toList())
    }
}
