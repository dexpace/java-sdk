/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RFC 7616 `Digest` authentication handler.
 *
 * Supports the `MD5`, `MD5-sess`, `SHA-256`, and `SHA-256-sess` algorithms with
 * `qop=auth`. The following are intentionally declined for v1 (per Rank 12 spec):
 *
 * - `qop=auth-int` only (no `auth`) — would require body hashing and breaks streaming.
 * - `algorithm=SHA-512-256` and any other unsupported algorithm.
 * - Mutual-auth (`rspauth`) verification on the response (RFC 7616 §3.5).
 *
 * The handler picks the first challenge whose algorithm appears earliest in
 * [preferredAlgorithms] — by default modern SHA-256 variants are preferred over
 * legacy MD5.
 *
 * Thread-safety: safe across concurrent requests. The nonce counter is tracked
 * per server nonce in a bounded [ConcurrentHashMap] of [AtomicLong]s (RFC 7616
 * §3.4 requires `nc` to start at `00000001` for each fresh nonce and increment
 * only on reuse of that same nonce); `MessageDigest` is not thread-safe and is
 * therefore instantiated per call (microsecond cost, acceptable per spec).
 *
 * The client nonce (`cnonce`) is drawn from a process-wide
 * [java.security.SecureRandom] (RFC 7616 §3.4.6 relies on an unpredictable client
 * nonce to defend the password hash against precomputation attacks).
 *
 * If the JVM lacks `MD5` or `SHA-256` (should never happen), the handler throws
 * `IllegalStateException` wrapping the underlying `NoSuchAlgorithmException`.
 */
public class DigestChallengeHandler
    @JvmOverloads
    constructor(
        private val username: String,
        private val password: String,
        private val preferredAlgorithms: List<DigestAlgorithm> = DEFAULT_PREFERENCE,
    ) : ChallengeHandler {
        init {
            require(username.isNotBlank()) { "username must not be blank" }
            require(password.isNotBlank()) { "password must not be blank" }
            require(preferredAlgorithms.isNotEmpty()) { "preferredAlgorithms must not be empty" }
        }

        /**
         * Per-server-nonce request counters. RFC 7616 §3.4 requires `nc` to be
         * `00000001` for the first request against a given nonce and to increment only
         * when that same nonce is reused, so a single shared counter would emit `nc>1`
         * on the first request against a new server (or after a `stale=true` rotation).
         *
         * The map is bounded to [MAX_TRACKED_NONCES] entries: after a nonce is admitted the
         * map is drained back under that cap, so memory stays bounded even against a server
         * that rotates nonces aggressively. Evicting a still-live nonce only resets its `nc`
         * to `1` on the next reuse — harmless, since a fresh nonce legitimately starts at `1`
         * anyway.
         */
        private val nonceCounters = ConcurrentHashMap<String, AtomicLong>()

        override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean = pickChallenge(challenges) != null

        override fun handleChallenges(
            method: Method,
            uri: URI,
            challenges: List<AuthenticateChallenge>,
            isProxy: Boolean,
        ): AuthorizationHeader? {
            val picked = pickChallenge(challenges) ?: return null
            val headerValue = buildAuthorization(method, uri, picked.first, picked.second)
            val headerName = if (isProxy) "Proxy-Authorization" else "Authorization"
            return AuthorizationHeader(headerName, headerValue)
        }

        /**
         * Walks the challenge list and returns the first (challenge, algorithm) pair we
         * can satisfy. A challenge is satisfiable iff:
         * - scheme is "Digest" (case-insensitive),
         * - its `algorithm` parameter maps to a supported [DigestAlgorithm] (or is
         *   absent — RFC 7616 §3.3 says MD5 is the default),
         * - its `qop` parameter contains `auth` (we don't do `auth-int`), or `qop` is
         *   absent (legacy RFC 2069 style — we'll still handle it),
         * - it carries a `realm` and `nonce`.
         *
         * Ordering: we scan [preferredAlgorithms] first, then within that scan the
         * candidates. This ensures SHA-256 wins over MD5 even when MD5 appears first
         * in the header.
         *
         * Per-challenge validation is delegated to [toCandidate]; this scan only applies
         * the algorithm-priority ordering, independent of the order challenges arrived in.
         */
        private fun pickChallenge(
            challenges: List<AuthenticateChallenge>,
        ): Pair<AuthenticateChallenge, DigestAlgorithm>? {
            // Find all challenges that match Digest with a satisfiable qop/realm/nonce
            // — we'll filter by algorithm preference below.
            val candidates = challenges.mapNotNull(::toCandidate)
            // Pick the candidate whose algorithm appears earliest in our preference list.
            // Returns null when no candidate matches any preferred algorithm.
            for (preferred in preferredAlgorithms) {
                candidates.firstOrNull { it.second == preferred }?.let { return it }
            }
            return null
        }

        /**
         * Maps a single challenge to a satisfiable (challenge, algorithm) candidate, or null
         * when it is not usable: it must be a `Digest` challenge carrying `realm`, `nonce`, and
         * a supported `qop`. Its algorithm is then resolved — an absent `algorithm` parameter
         * defaults to MD5 (RFC 7616 §3.3), and an unsupported one is declined.
         */
        private fun toCandidate(challenge: AuthenticateChallenge): Pair<AuthenticateChallenge, DigestAlgorithm>? {
            val isInvalidChallenge =
                !challenge.scheme.equals("Digest", ignoreCase = true) ||
                    challenge.parameters["realm"] == null ||
                    challenge.parameters["nonce"] == null ||
                    !qopSupportsAuth(challenge.parameters["qop"])

            if (isInvalidChallenge) {
                return null
            }

            val algorithmName = challenge.parameters["algorithm"] ?: return challenge to DigestAlgorithm.MD5

            return DigestAlgorithm.fromString(algorithmName)?.let {
                challenge to it
            }
        }

        /**
         * Builds the `Authorization: Digest ...` header value for a single challenge.
         * Follows RFC 7616 §3.4.6 — HA1, HA2, response — plus the standard parameter
         * set the server expects to mirror.
         */
        private fun buildAuthorization(
            method: Method,
            uri: URI,
            challenge: AuthenticateChallenge,
            algorithm: DigestAlgorithm,
        ): String {
            val realm = challenge.parameters["realm"] ?: error("Digest challenge missing realm")
            val nonce = challenge.parameters["nonce"] ?: error("Digest challenge missing nonce")
            val opaque = challenge.parameters["opaque"]
            val qop = pickQopToken(challenge.parameters["qop"])
            val uriValue = digestUri(uri)
            val nc = ncHex(nextNonceCount(nonce))
            val cnonce = generateCnonce()
            val charset = resolveCharset(challenge.parameters["charset"])

            val ha1 = computeHa1(algorithm, realm, nonce, cnonce, charset)
            val ha2 = computeHa2(algorithm, method.method, uriValue, charset)
            val response =
                if (qop != null) {
                    // RFC 7616 §3.4.1 — qop variant
                    hash(algorithm, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2", charset)
                } else {
                    // RFC 2069 legacy — no qop
                    hash(algorithm, "$ha1:$nonce:$ha2", charset)
                }

            val sb = StringBuilder(AUTH_HEADER_INITIAL_CAP).append("Digest ")
            appendQuoted(sb, "username", username)
            sb.append(", ")
            appendQuoted(sb, "realm", realm)
            sb.append(", ")
            appendQuoted(sb, "nonce", nonce)
            sb.append(", ")
            appendQuoted(sb, "uri", uriValue)
            if (qop != null) {
                sb.append(", qop=").append(qop)
                sb.append(", nc=").append(nc)
                sb.append(", ")
                appendQuoted(sb, "cnonce", cnonce)
            }
            sb.append(", ")
            appendQuoted(sb, "response", response)
            sb.append(", algorithm=").append(algorithm.headerName)
            if (opaque != null) {
                sb.append(", ")
                appendQuoted(sb, "opaque", opaque)
            }
            return sb.toString()
        }

        /** HA1 = H(username:realm:password); -sess wraps it with nonce + cnonce per RFC 7616 §3.4.2. */
        private fun computeHa1(
            algorithm: DigestAlgorithm,
            realm: String,
            nonce: String,
            cnonce: String,
            charset: Charset,
        ): String {
            val base = hash(algorithm, "$username:$realm:$password", charset)
            return if (algorithm.sessionVariant) hash(algorithm, "$base:$nonce:$cnonce", charset) else base
        }

        /** HA2 = H(method:uri) for qop=auth (RFC 7616 §3.4.3). */
        private fun computeHa2(
            algorithm: DigestAlgorithm,
            method: String,
            uri: String,
            charset: Charset,
        ): String = hash(algorithm, "$method:$uri", charset)

        /**
         * Computes a hex-encoded digest using a freshly-instantiated [MessageDigest].
         * Wraps [NoSuchAlgorithmException] as `IllegalStateException` — this should
         * not happen for MD5 / SHA-256 on any conforming JVM, but the wrap gives a
         * clear actionable message if a stripped runtime is in play.
         *
         * [charset] picks the byte encoding used to materialise [input] before hashing
         * — RFC 7616 §3.4 mandates UTF-8 when the challenge advertises `charset=UTF-8`,
         * and ISO-8859-1 (the historical default) otherwise.
         */
        private fun hash(
            algorithm: DigestAlgorithm,
            input: String,
            charset: Charset,
        ): String {
            val md =
                try {
                    MessageDigest.getInstance(algorithm.javaName)
                } catch (e: NoSuchAlgorithmException) {
                    throw IllegalStateException(
                        "Digest algorithm '${algorithm.javaName}' is not available on this JVM",
                        e,
                    )
                }
            return toHex(md.digest(input.toByteArray(charset)))
        }

        /**
         * Resolves the byte encoding for hashing per RFC 7616 §3.4: UTF-8 when the
         * challenge advertises `charset=UTF-8` (case-insensitive), ISO-8859-1 otherwise.
         */
        private fun resolveCharset(charsetParameter: String?): Charset =
            if (charsetParameter != null && charsetParameter.equals("utf-8", ignoreCase = true)) {
                Charsets.UTF_8
            } else {
                Charsets.ISO_8859_1
            }

        /**
         * Picks `auth` from a comma-separated qop list, falling back to legacy null
         * (RFC 2069) when [qopParameter] itself is null. Returns null when no qop
         * negotiation is required.
         *
         * Equivalent to `if (qopParameter == null) null else if (qopOffersAuth) "auth" else null`
         * — [pickChallenge] uses [qopSupportsAuth] to filter, so a non-null parameter that
         * reaches this code is expected to contain `auth`, but we re-check defensively.
         */
        private fun pickQopToken(qopParameter: String?): String? {
            if (qopParameter == null) return null // legacy RFC 2069
            return if (qopSupportsAuth(qopParameter)) "auth" else null
        }

        /** Renders the digest-uri-value per RFC 7616 §3.4. We use the request-target form. */
        private fun digestUri(uri: URI): String {
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val query = uri.rawQuery
            return if (query != null) "$path?$query" else path
        }

        /**
         * Pads [count] to exactly 8 lower-case hex digits per RFC 7616 §3.4.
         *
         * The low 32 bits of [count] are used so that when the nonce counter wraps past
         * 2^32 the output remains exactly 8 hex characters rather than growing to 9+.
         * Masking with `0xFFFFFFFFL` extracts the unsigned low 32 bits before formatting.
         */
        private fun ncHex(count: Long): String = String.format(Locale.US, "%08x", count and U32_MASK)

        /**
         * Returns the next `nc` value for [nonce], starting at 1 for a nonce seen for the
         * first time and incrementing on each reuse (RFC 7616 §3.4).
         *
         * `computeIfAbsent` installs the per-nonce [AtomicLong] atomically, and the counter
         * itself is the single source of truth for the increment, so concurrent reuse of one
         * nonce stays correct. After admitting a (possibly new) nonce the map is drained back
         * under [MAX_TRACKED_NONCES] with a loop rather than a single pre-insert eviction: the
         * old check-then-evict-then-insert was a non-atomic race, and a burst of concurrent
         * distinct-nonce inserts could overshoot the cap and then stay there permanently (each
         * later insert evicting one and adding one). Draining after the insert keeps the map
         * converging back to the cap.
         */
        private fun nextNonceCount(nonce: String): Long {
            val count = nonceCounters.computeIfAbsent(nonce) { AtomicLong(0) }.incrementAndGet()
            while (nonceCounters.size > MAX_TRACKED_NONCES) {
                evictOldestNonce()
            }
            return count
        }

        /**
         * Evicts one entry to keep [nonceCounters] bounded. `ConcurrentHashMap` has no
         * insertion order, so any single key is an acceptable victim; we drop the first
         * the iterator yields. Re-admitting an evicted-but-still-live nonce simply restarts
         * its `nc` at 1, which is spec-legal for a fresh nonce.
         */
        private fun evictOldestNonce() {
            val iterator = nonceCounters.keys.iterator()
            if (iterator.hasNext()) {
                nonceCounters.remove(iterator.next())
            }
        }

        /**
         * Cryptographically-strong cnonce: [CNONCE_RANDOM_BYTES] bytes (≥128 bits) drawn
         * from a process-wide [SecureRandom], hex-encoded. RFC 7616 §3.4.6 requires the
         * client nonce to be unpredictable so it cannot be precomputed by an attacker.
         */
        private fun generateCnonce(): String {
            val bytes = ByteArray(CNONCE_RANDOM_BYTES)
            SECURE_RANDOM.nextBytes(bytes)
            return toHex(bytes)
        }

        public companion object {
            private val DEFAULT_PREFERENCE: List<DigestAlgorithm> =
                listOf(
                    DigestAlgorithm.SHA_256,
                    DigestAlgorithm.SHA_256_SESS,
                    DigestAlgorithm.MD5,
                    DigestAlgorithm.MD5_SESS,
                )

            private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

            /**
             * Process-wide CSPRNG for the Digest client nonce (`cnonce`). A single shared
             * instance is the documented JDK pattern: `SecureRandom` is thread-safe, and
             * sharing one avoids per-handler seeding cost while still drawing from a
             * cryptographically-strong source (RFC 7616 §3.4.6, S-3).
             */
            private val SECURE_RANDOM: SecureRandom = SecureRandom()

            // Initial capacity for the assembled `Authorization: Digest …` header. 256 chars
            // comfortably covers RFC 7616 fields (realm, nonce, cnonce, response hex …)
            // without resize.
            private const val AUTH_HEADER_INITIAL_CAP = 256

            // Random bytes drawn for each cnonce. 16 bytes = 128 bits of entropy, the floor
            // RFC 7616 §3.4.6 expects from an unpredictable client nonce.
            private const val CNONCE_RANDOM_BYTES = 16

            // Upper bound on distinct server nonces tracked for per-nonce `nc` counting.
            // Beyond this the least-recently-admitted nonce is evicted, keeping memory
            // bounded against a server that rotates nonces aggressively (RFC 7616 §3.4, S-5).
            private const val MAX_TRACKED_NONCES = 1024

            // Bits per hex nibble.
            private const val NIBBLE_BITS = 4

            // Low-byte mask for byte→int conversion before hex formatting.
            private const val BYTE_MASK = 0xff

            // Unsigned-32-bit mask used to clip the Digest nonce counter to the wire format
            // (RFC 7616 §3.4: `nc` is 8 hex chars = 32 bits).
            private const val U32_MASK = 0xFFFFFFFFL

            /** True if [qop] is absent (legacy) or contains the `auth` token (case-insensitive). */
            private fun qopSupportsAuth(qop: String?): Boolean {
                if (qop == null) return true
                return qop.split(',').any { it.trim().equals("auth", ignoreCase = true) }
            }

            /** Lower-case hex of a byte array — minimal allocation, no intermediate strings. */
            private fun toHex(bytes: ByteArray): String {
                val out = CharArray(bytes.size * 2)
                var i = 0
                for (b in bytes) {
                    val v = b.toInt() and BYTE_MASK
                    out[i++] = HEX_DIGITS[v ushr NIBBLE_BITS]
                    out[i++] = HEX_DIGITS[v and 0x0f]
                }
                return String(out)
            }

            /** Appends `key="<quoted-value>"` with `\"` and `\\` escaping per RFC 7235. */
            private fun appendQuoted(
                sb: StringBuilder,
                key: String,
                value: String,
            ) {
                sb.append(key).append("=\"")
                for (c in value) {
                    if (c == '\\' || c == '"') sb.append('\\')
                    sb.append(c)
                }
                sb.append('"')
            }
        }
    }
