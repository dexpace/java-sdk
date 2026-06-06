/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import java.net.URL
import java.net.URLDecoder

/**
 * Redacts sensitive parts of a URL for safe logging.
 *
 * Two redactions are applied:
 *  - **Userinfo** (`user:password@`) is ALWAYS replaced with `***:***@`. This is the single
 *    most credential-leaking part of a URL, so the allow-list does not apply here.
 *  - **Query parameter values** are replaced with `***` unless the parameter name (decoded,
 *    compared case-insensitively) appears in the supplied allow-list. Multi-value keys are
 *    treated atomically — either all values for a key are kept, or all are redacted.
 *  - **Fragment tokens** that have the form `key=value` are redacted under the same allow-list
 *    as query parameters. Plain fragments (no `=`) are preserved verbatim.
 *
 * The path and scheme are not modified.
 *
 * Designed for hot-path logging: the fast path (no `?`, no userinfo) returns
 * `url.toString()` with no rebuild. On any unexpected failure, returns `"[malformed url]"`
 * rather than throwing — logging must never break the caller.
 *
 * Thread-safe: stateless singleton.
 */
public object UrlRedactor {
    /**
     * Default allow-list: just `api-version`, the canonical safe-to-log REST query param.
     * This allow-list also governs fragment redaction — `key=value` tokens in the fragment
     * are redacted under the same rules as query parameters.
     */
    @JvmField
    public val DEFAULT_ALLOWED: Set<String> = setOf("api-version")

    private const val REDACTED = "***"

    /**
     * Pre-encoded form of [REDACTED] for query-value substitution. The three asterisks
     * are all unreserved-equivalent under `application/x-www-form-urlencoded`, so the
     * encoded representation is identical to the raw literal — no `URLEncoder.encode`
     * call is needed on the hot path.
     */
    private const val REDACTED_ENCODED = REDACTED
    private const val USERINFO_REDACTED = "***:***"
    private const val MALFORMED = "[malformed url]"

    // StringBuilder slack reserved for the userinfo + scheme insertion on rebuild. Tuned,
    // not load-bearing.
    private const val REBUILD_BUILDER_SLACK = 16

    /**
     * Returns a loggable URL string with userinfo and disallowed query values redacted.
     *
     * @param url the URL to redact; must not be null.
     * @param allowedQueryParams query parameter names (case-insensitive, decoded form) whose
     *   values should be kept. Empty set means redact every query value.
     */
    @JvmStatic
    @JvmOverloads
    public fun redact(
        url: URL,
        allowedQueryParams: Set<String> = DEFAULT_ALLOWED,
    ): String =
        try {
            val raw = url.toString()
            val hasUserInfo = url.userInfo != null
            val hasQuery = raw.indexOf('?') >= 0
            val hasFragment = url.ref != null

            if (!hasUserInfo && !hasQuery && !hasFragment) {
                raw
            } else {
                rebuild(url, raw, lowercaseAllowList(allowedQueryParams))
            }
        } catch (t: Throwable) {
            MALFORMED
        }

    private fun lowercaseAllowList(allowed: Set<String>): Set<String> {
        if (allowed.isEmpty()) return emptySet()
        val lower = HashSet<String>(allowed.size)
        for (name in allowed) lower.add(name.lowercase())
        return lower
    }

    private fun rebuild(
        url: URL,
        raw: String,
        allowedLower: Set<String>,
    ): String {
        val out = StringBuilder(raw.length + REBUILD_BUILDER_SLACK)
        out.append(url.protocol).append("://")

        if (url.userInfo != null) {
            out.append(USERINFO_REDACTED).append('@')
        }

        out.append(url.host)
        if (url.port != -1) {
            out.append(':').append(url.port)
        }

        val path = url.path
        if (!path.isNullOrEmpty()) {
            out.append(path)
        }

        // Preserve `?` even if the query is empty — some servers care.
        if (raw.indexOf('?') >= 0) {
            out.append('?')
            val rawQuery = url.query
            if (!rawQuery.isNullOrEmpty()) {
                appendRedactedQuery(out, rawQuery, allowedLower)
            }
        }

        val fragment = url.ref
        if (fragment != null) {
            out.append('#')
            appendRedactedQuery(out, fragment, allowedLower)
        }

        return out.toString()
    }

    private fun appendRedactedQuery(
        out: StringBuilder,
        rawQuery: String,
        allowedLower: Set<String>,
    ) {
        var first = true
        var cursor = 0
        val length = rawQuery.length
        while (cursor < length) {
            val ampIndex = rawQuery.indexOf('&', cursor)
            val end = if (ampIndex < 0) length else ampIndex
            val pair = rawQuery.substring(cursor, end)
            if (!first) out.append('&')
            first = false
            appendRedactedPair(out, pair, allowedLower)
            cursor = end + 1
        }
        // Trailing `&` — for both query strings and fragment strings — produces a final
        // empty pair which the loop above swallows (appendRedactedPair is a no-op on an
        // empty pair). We intentionally do not re-emit the trailing `&`; an input of
        // "a=1&" or a fragment of "a=1&" would produce "a=1" or "a=***" without the
        // trailing separator. This is intentional: an empty trailing pair is almost always
        // a malformed input, and re-emitting it would make the redacted output equally
        // malformed. The same behaviour applies when this function is called for fragment
        // redaction (the '#' prefix is appended by the caller before this function is invoked).
    }

    private fun appendRedactedPair(
        out: StringBuilder,
        pair: String,
        allowedLower: Set<String>,
    ) {
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

        val allowed = allowedLower.contains(safeDecode(encodedName).lowercase())

        out.append(encodedName)
        if (hasValue) {
            out.append('=')
            if (allowed) {
                out.append(encodedValue)
            } else {
                out.append(REDACTED_ENCODED)
            }
        }
    }

    private fun safeDecode(encoded: String): String =
        try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (t: Throwable) {
            encoded
        }
}
