package org.dexpace.sdk.core.http.auth

/**
 * A single challenge parsed from a `WWW-Authenticate` or `Proxy-Authenticate` header.
 *
 * One header may contain multiple challenges (comma-separated at the top level); each
 * challenge has a scheme (e.g. `Basic`, `Digest`, `Bearer`) and a map of auth-params
 * (`realm`, `nonce`, `qop`, `opaque`, `algorithm`, ...). Parameter names are stored in
 * lower case for case-insensitive lookup; quoted-string values have their surrounding
 * quotes removed and embedded escapes (`\"`, `\\`) unescaped.
 *
 * Construct via [AuthChallengeParser.parse]. Tokens such as Bearer's `token68` value
 * are recorded under the synthetic key `"token68"`.
 */
data class AuthenticateChallenge(
    val scheme: String,
    val parameters: Map<String, String>,
)
