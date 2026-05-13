package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.util.Uuids
import java.net.URI
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
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
 * Thread-safety: safe across concurrent requests. The nonce counter is an
 * [AtomicLong]; `MessageDigest` is not thread-safe and is therefore instantiated
 * per call (microsecond cost, acceptable per spec).
 *
 * If the JVM lacks `MD5` or `SHA-256` (should never happen), the handler throws
 * `IllegalStateException` wrapping the underlying `NoSuchAlgorithmException`.
 */
class DigestChallengeHandler @JvmOverloads constructor(
    private val username: String,
    private val password: String,
    private val preferredAlgorithms: List<DigestAlgorithm> = DEFAULT_PREFERENCE,
) : ChallengeHandler {

    init {
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
        require(preferredAlgorithms.isNotEmpty()) { "preferredAlgorithms must not be empty" }
    }

    private val nonceCount = AtomicLong(0)

    override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean =
        pickChallenge(challenges) != null

    override fun handleChallenges(
        method: Method,
        uri: URI,
        challenges: List<AuthenticateChallenge>,
        isProxy: Boolean,
    ): Pair<String, String>? {
        val picked = pickChallenge(challenges) ?: return null
        val headerValue = buildAuthorization(method, uri, picked.first, picked.second)
        val headerName = if (isProxy) "Proxy-Authorization" else "Authorization"
        return headerName to headerValue
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
     * challenges. This ensures SHA-256 wins over MD5 even when MD5 appears first
     * in the header.
     */
    private fun pickChallenge(
        challenges: List<AuthenticateChallenge>,
    ): Pair<AuthenticateChallenge, DigestAlgorithm>? {
        // Find all challenges that match Digest with a satisfiable qop/realm/nonce
        // — we'll filter by algorithm preference below.
        val candidates = ArrayList<Pair<AuthenticateChallenge, DigestAlgorithm>>(challenges.size)
        for (challenge in challenges) {
            if (!challenge.scheme.equals("Digest", ignoreCase = true)) continue
            if (challenge.parameters["realm"] == null) continue
            if (challenge.parameters["nonce"] == null) continue
            if (!qopSupportsAuth(challenge.parameters["qop"])) continue
            val algorithmName = challenge.parameters["algorithm"]
            val algorithm = if (algorithmName == null) {
                DigestAlgorithm.MD5 // RFC 7616 §3.3: MD5 is the default when omitted.
            } else {
                DigestAlgorithm.fromString(algorithmName) ?: continue
            }
            candidates.add(challenge to algorithm)
        }
        // Pick the candidate whose algorithm appears earliest in our preference list.
        // Returns null when no candidate matches any preferred algorithm.
        for (preferred in preferredAlgorithms) {
            candidates.firstOrNull { it.second == preferred }?.let { return it }
        }
        return null
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
        val realm = challenge.parameters["realm"]
            ?: throw IllegalStateException("Digest challenge missing realm")
        val nonce = challenge.parameters["nonce"]
            ?: throw IllegalStateException("Digest challenge missing nonce")
        val opaque = challenge.parameters["opaque"]
        val qop = pickQopToken(challenge.parameters["qop"])
        val uriValue = digestUri(uri)
        val nc = ncHex(nonceCount.incrementAndGet())
        val cnonce = generateCnonce()
        val charset = resolveCharset(challenge.parameters["charset"])

        val ha1 = computeHa1(algorithm, realm, nonce, cnonce, charset)
        val ha2 = computeHa2(algorithm, method.method, uriValue, charset)
        val response = if (qop != null) {
            // RFC 7616 §3.4.1 — qop variant
            hash(algorithm, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2", charset)
        } else {
            // RFC 2069 legacy — no qop
            hash(algorithm, "$ha1:$nonce:$ha2", charset)
        }

        val sb = StringBuilder(256).append("Digest ")
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
    private fun hash(algorithm: DigestAlgorithm, input: String, charset: Charset): String {
        val md = try {
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
    private fun ncHex(count: Long): String = String.format(Locale.US, "%08x", count and 0xFFFFFFFFL)

    /** Non-blocking cnonce: 16 hex chars (high 64 bits of a fresh type-4 UUID). */
    private fun generateCnonce(): String {
        val msb = Uuids.random().mostSignificantBits
        val sb = StringBuilder(16)
        // Render as 16 lower-case hex chars; preserve leading zeros.
        for (i in 15 downTo 0) {
            val nibble = ((msb ushr (i * 4)) and 0xfL).toInt()
            sb.append(HEX_DIGITS[nibble])
        }
        return sb.toString()
    }

    companion object {
        private val DEFAULT_PREFERENCE: List<DigestAlgorithm> = listOf(
            DigestAlgorithm.SHA_256,
            DigestAlgorithm.SHA_256_SESS,
            DigestAlgorithm.MD5,
            DigestAlgorithm.MD5_SESS,
        )

        private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

        /** True if [qop] is absent (legacy) or contains the `auth` token (case-insensitive). */
        private fun qopSupportsAuth(qop: String?): Boolean {
            if (qop == null) return true
            for (token in qop.split(',')) {
                if (token.trim().equals("auth", ignoreCase = true)) return true
            }
            return false
        }

        /** Lower-case hex of a byte array — minimal allocation, no intermediate strings. */
        private fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            var i = 0
            for (b in bytes) {
                val v = b.toInt() and 0xff
                out[i++] = HEX_DIGITS[v ushr 4]
                out[i++] = HEX_DIGITS[v and 0x0f]
            }
            return String(out)
        }

        /** Appends `key="<quoted-value>"` with `\"` and `\\` escaping per RFC 7235. */
        private fun appendQuoted(sb: StringBuilder, key: String, value: String) {
            sb.append(key).append("=\"")
            for (c in value) {
                if (c == '\\' || c == '"') sb.append('\\')
                sb.append(c)
            }
            sb.append('"')
        }
    }
}
