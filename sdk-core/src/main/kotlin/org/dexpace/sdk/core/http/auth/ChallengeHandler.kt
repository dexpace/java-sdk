package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI

/**
 * A named authorization header (name + value) returned by [ChallengeHandler.handleChallenges].
 *
 * Replaces the raw `Pair<String, String>` return type to make call sites self-documenting:
 * `header.name` and `header.value` read clearly compared to `.first` and `.second`.
 */
public data class AuthorizationHeader(
    val name: String,
    val value: String,
)

/**
 * Strategy for handling `WWW-Authenticate` (or `Proxy-Authenticate`) challenges.
 *
 * A handler inspects the parsed challenge list and either produces the
 * [AuthorizationHeader] to set on the retry request, or returns `null` to
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
public interface ChallengeHandler {

    /**
     * Returns the header to set on the retry request, or `null` if this handler
     * cannot satisfy any offered challenge. The `method` and `uri` are required
     * for schemes (like Digest) that sign the request line.
     *
     * The returned [AuthorizationHeader] name is `Authorization` for `WWW-Authenticate`
     * challenges and `Proxy-Authorization` for `Proxy-Authenticate`. Callers signal
     * which via [isProxy].
     *
     * Kotlin callers can omit [isProxy] thanks to the default value; Java callers
     * use the three-argument convenience overload below.
     */
    public fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
        isProxy: Boolean = false,
    ): AuthorizationHeader?

    /**
     * Three-argument convenience overload that delegates to the full form with
     * `isProxy = false`. Exists so Java callers see two distinct methods rather
     * than having to pass the `isProxy` argument explicitly on every call —
     * `@JvmOverloads` is disallowed on interface methods, so the default-argument
     * Kotlin form alone does not produce a Java-visible overload.
     */
    public fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
    ): AuthorizationHeader? = handleChallenges(method, uri, challenges, false)

    /** True if any offered challenge is one this handler can satisfy. */
    public fun canHandle(challenges: List<AuthenticateChallenge>): Boolean

    public companion object {
        /**
         * Composes [handlers] into one. The composite delegates to the first
         * handler whose [canHandle] returns true. Order matters — register Digest
         * before Basic when both might apply.
         */
        @JvmStatic
        public fun of(vararg handlers: ChallengeHandler): ChallengeHandler =
            CompositeChallengeHandler(handlers.toList())
    }
}
