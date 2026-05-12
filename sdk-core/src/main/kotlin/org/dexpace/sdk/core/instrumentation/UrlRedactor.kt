package org.dexpace.sdk.core.instrumentation

import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Redacts sensitive parts of a URL for safe logging.
 *
 * Two redactions are applied:
 *  - **Userinfo** (`user:password@`) is ALWAYS replaced with `***:***@`. This is the single
 *    most credential-leaking part of a URL, so the allow-list does not apply here.
 *  - **Query parameter values** are replaced with `***` unless the parameter name (decoded,
 *    compared case-insensitively) appears in the supplied allow-list. Multi-value keys are
 *    treated atomically — either all values for a key are kept, or all are redacted.
 *
 * The fragment is preserved verbatim; the path and scheme are not modified.
 *
 * Designed for hot-path logging: the fast path (no `?`, no userinfo) returns
 * `url.toString()` with no rebuild. On any unexpected failure, returns `"[malformed url]"`
 * rather than throwing — logging must never break the caller.
 *
 * Thread-safe: stateless singleton.
 */
object UrlRedactor {

    /** Default allow-list: just `api-version`, the canonical safe-to-log REST query param. */
    @JvmField
    val DEFAULT_ALLOWED: Set<String> = setOf("api-version")

    private const val REDACTED = "***"
    private const val USERINFO_REDACTED = "***:***"
    private const val MALFORMED = "[malformed url]"

    /**
     * Returns a loggable URL string with userinfo and disallowed query values redacted.
     *
     * @param url the URL to redact; must not be null.
     * @param allowedQueryParams query parameter names (case-insensitive, decoded form) whose
     *   values should be kept. Empty set means redact every query value.
     */
    @JvmStatic
    @JvmOverloads
    fun redact(url: URL, allowedQueryParams: Set<String> = DEFAULT_ALLOWED): String {
        return try {
            val raw = url.toString()
            val userInfo = url.userInfo
            val hasQuery = raw.indexOf('?') >= 0

            if (userInfo == null && !hasQuery) {
                return raw
            }

            val allowedLower = if (allowedQueryParams.isEmpty()) {
                emptySet()
            } else {
                val s = HashSet<String>(allowedQueryParams.size)
                for (name in allowedQueryParams) s.add(name.lowercase())
                s
            }

            rebuild(url, raw, allowedLower)
        } catch (t: Throwable) {
            MALFORMED
        }
    }

    private fun rebuild(url: URL, raw: String, allowedLower: Set<String>): String {
        val out = StringBuilder(raw.length + 16)
        out.append(url.protocol).append("://")

        val userInfo = url.userInfo
        if (userInfo != null) {
            out.append(USERINFO_REDACTED).append('@')
        }

        out.append(url.host)
        val port = url.port
        if (port != -1) {
            out.append(':').append(port)
        }

        val path = url.path
        if (!path.isNullOrEmpty()) {
            out.append(path)
        }

        // Preserve `?` even if the query is empty — some servers care.
        val qIdx = raw.indexOf('?')
        if (qIdx >= 0) {
            out.append('?')
            val rawQuery = url.query
            if (!rawQuery.isNullOrEmpty()) {
                appendRedactedQuery(out, rawQuery, allowedLower)
            }
        }

        val fragment = url.ref
        if (fragment != null) {
            out.append('#').append(fragment)
        }

        return out.toString()
    }

    private fun appendRedactedQuery(out: StringBuilder, rawQuery: String, allowedLower: Set<String>) {
        var first = true
        var i = 0
        val n = rawQuery.length
        while (i < n) {
            var end = rawQuery.indexOf('&', i)
            if (end < 0) end = n
            val pair = rawQuery.substring(i, end)
            if (!first) out.append('&')
            first = false
            appendRedactedPair(out, pair, allowedLower)
            i = end + 1
        }
        // Trailing `&` produces a final empty pair which the loop above swallows; we
        // intentionally do not re-emit it (the input was likely malformed anyway).
    }

    private fun appendRedactedPair(out: StringBuilder, pair: String, allowedLower: Set<String>) {
        if (pair.isEmpty()) return
        val eq = pair.indexOf('=')
        val encodedName: String
        val hasValue: Boolean
        val encodedValue: String
        if (eq < 0) {
            encodedName = pair
            hasValue = false
            encodedValue = ""
        } else {
            encodedName = pair.substring(0, eq)
            hasValue = true
            encodedValue = pair.substring(eq + 1)
        }

        val decodedName = safeDecode(encodedName)
        val allowed = allowedLower.contains(decodedName.lowercase())

        out.append(encodedName)
        if (hasValue) {
            out.append('=')
            if (allowed) {
                out.append(encodedValue)
            } else {
                // URLEncoder uses application/x-www-form-urlencoded rules: space -> '+'.
                // Acceptable for the literal `***` which has no special characters.
                out.append(URLEncoder.encode(REDACTED, "UTF-8"))
            }
        }
    }

    private fun safeDecode(encoded: String): String {
        return try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (t: Throwable) {
            encoded
        }
    }
}
