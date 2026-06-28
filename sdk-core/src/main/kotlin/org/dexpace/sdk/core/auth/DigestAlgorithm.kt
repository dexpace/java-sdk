/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import java.util.Locale

/**
 * Digest auth algorithms supported by [DigestChallengeHandler].
 *
 * Per RFC 7616, an `-sess` variant computes HA1 as
 * `H(H(username:realm:password):nonce:cnonce)` so that the per-session HA1 is
 * keyed by the (nonce, cnonce) pair rather than being a static derivation of the
 * password. Non-session variants compute HA1 as `H(username:realm:password)`.
 *
 * [javaName] is the algorithm string passed to `MessageDigest.getInstance(...)`.
 * Both session and non-session variants share the same JDK algorithm — what
 * changes is how HA1 is constructed (see [DigestChallengeHandler]).
 *
 * The header `algorithm` parameter emitted on the `Authorization` reply uses the
 * full RFC 7616 spelling (e.g. `SHA-256-sess`) via [headerName].
 *
 * SHA-512-256 (RFC 7616) is intentionally NOT supported in v1; servers offering
 * only that algorithm will fall through to `canHandle == false`.
 */
public enum class DigestAlgorithm(
    public val javaName: String,
    public val sessionVariant: Boolean,
    public val headerName: String,
) {
    MD5("MD5", false, "MD5"),
    MD5_SESS("MD5", true, "MD5-sess"),
    SHA_256("SHA-256", false, "SHA-256"),
    SHA_256_SESS("SHA-256", true, "SHA-256-sess"),
    ;

    public companion object {
        /**
         * Parses the RFC 7616 `algorithm` parameter (case-insensitive). Returns
         * null for absent / unsupported algorithms (e.g. `SHA-512-256`).
         */
        @JvmStatic
        public fun fromString(raw: String): DigestAlgorithm? =
            when (raw.uppercase(Locale.US)) {
                "MD5" -> MD5
                "MD5-SESS" -> MD5_SESS
                "SHA-256" -> SHA_256
                "SHA-256-SESS" -> SHA_256_SESS
                else -> null
            }
    }
}
