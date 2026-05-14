package org.dexpace.sdk.core.http.auth

/**
 * Per-request auth metadata describing which [AuthScheme]s an operation supports and any
 * OAuth-specific parameters (scopes / extra params) the auth step should forward to the
 * [BearerTokenProvider].
 *
 * **Not currently wired into [org.dexpace.sdk.core.http.pipeline.steps.AuthStep]'s lookup
 * path.** [org.dexpace.sdk.core.http.context.RequestContext] is presently a fixed-field
 * `data class` with no typed-key attachment mechanism, so there is no general way to
 * attach an `AuthMetadata` to a request and have the step retrieve it. Wiring this into
 * per-request lookup is deferred until the SDK adopts a typed-key context store on
 * `RequestContext`; the type is defined here so downstream code (operation generators,
 * documentation, future wiring) can reference it without a parallel refactor blocking
 * Rank 6.
 *
 * @param schemes the auth schemes this operation supports, in preference order. Must not be empty.
 * @param oauthScopes OAuth scopes to forward to the token provider; ignored for non-OAuth schemes.
 * @param oauthParams extra OAuth params to forward (e.g. `claims`); defaults to empty.
 * @throws IllegalArgumentException if [schemes] is empty.
 */
public class AuthMetadata
    @JvmOverloads
    constructor(
        public val schemes: List<AuthScheme>,
        public val oauthScopes: List<String> = emptyList(),
        public val oauthParams: Map<String, Any> = emptyMap(),
    ) {
        init {
            require(schemes.isNotEmpty()) { "schemes must not be empty" }
        }
    }
