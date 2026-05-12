package org.dexpace.sdk.core.http.auth

/**
 * HTTP authentication schemes recognised by the SDK.
 *
 * Used in [AuthMetadata] to describe the schemes a per-operation request supports, and to
 * mark requests that must skip auth ([NO_AUTH]).
 */
@Suppress("unused")
enum class AuthScheme {
    /** OAuth 2.0 bearer tokens. */
    OAUTH2,

    /** Static API keys stamped into a configured header. */
    API_KEY,

    /** RFC 7617 HTTP Basic auth. */
    BASIC,

    /** RFC 7616 HTTP Digest auth. */
    DIGEST,

    /**
     * Explicit "skip auth" marker — present on per-request [AuthMetadata] to bypass an
     * otherwise-configured credential step (e.g. anonymous probes against an authenticated
     * service).
     */
    NO_AUTH,
}
