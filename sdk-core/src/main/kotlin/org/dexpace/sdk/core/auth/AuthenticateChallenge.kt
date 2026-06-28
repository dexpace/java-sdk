/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

/**
 * A single challenge parsed from a `WWW-Authenticate` or `Proxy-Authenticate` header.
 *
 * One header may contain multiple challenges (comma-separated at the top level); each
 * challenge has a [scheme] (e.g. `basic`, `digest`, `bearer`) and a map of auth-params
 * (`realm`, `nonce`, `qop`, `opaque`, `algorithm`, ...). Both scheme and parameter names
 * are normalised to lower case for case-insensitive lookup; quoted-string values have
 * their surrounding quotes removed and embedded escapes (`\"`, `\\`) unescaped.
 *
 * Construct via [AuthChallengeParser.parse]. Tokens such as Bearer's `token68` value
 * are recorded under the synthetic key `"token68"`.
 *
 * @property scheme the auth scheme, normalised to lower case.
 * @property parameters auth-param map; keys are lower-cased, values are stored verbatim.
 */
public data class AuthenticateChallenge(
    val scheme: String,
    val parameters: Map<String, String>,
)
