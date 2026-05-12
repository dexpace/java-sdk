package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI

/**
 * Composes multiple [ChallengeHandler]s — first-handler-that-can-handle wins.
 *
 * Construct via [ChallengeHandler.of]. The constructor takes a defensive copy of
 * the supplied list, so subsequent mutation of the caller's array does not affect
 * the composite's ordering.
 *
 * Thread-safety: the wrapped handlers are required to be thread-safe across
 * requests; this composite is stateless beyond the immutable handler list.
 */
internal class CompositeChallengeHandler(
    handlers: List<ChallengeHandler>,
) : ChallengeHandler {

    private val handlers: List<ChallengeHandler> = handlers.toList()

    override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean {
        for (handler in handlers) {
            if (handler.canHandle(challenges)) return true
        }
        return false
    }

    override fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
        isProxy: Boolean,
    ): Pair<String, String>? {
        for (handler in handlers) {
            if (handler.canHandle(challenges)) {
                return handler.handleChallenges(method, uri, challenges, isProxy)
            }
        }
        return null
    }
}
