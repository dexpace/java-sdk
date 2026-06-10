/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Internal helpers for building the next-page [Request] from an initial [Request] plus a
 * paging-state mutation.
 *
 * The functions here are pure URL-rewriting utilities — no I/O, no mutation of the
 * supplied [Request], and no allocation beyond the new request and URL. They exist so
 * pagination strategies can stay focused on extracting paging state from responses, while
 * the mechanical work of "produce a new request with this query param set" lives in one
 * place.
 *
 * ## URL encoding
 *
 * All query manipulation uses `application/x-www-form-urlencoded` semantics (the JDK's
 * `URLEncoder` / `URLDecoder` with UTF-8) — the same convention browsers and servers use
 * for query strings. Pre-existing query segments are preserved verbatim; only the targeted
 * parameter is replaced.
 *
 * ## URL.fragment / userInfo
 *
 * The rebuilder preserves the URL's `userInfo`, `host`, `port`, `path`, and `ref`
 * (fragment) exactly. Only the query string is mutated.
 */
internal object RequestRebuilder {
    private const val UTF_8: String = "UTF-8"

    /**
     * Returns a new [Request] cloned from [request] with the query parameter [name] set to
     * [value]. If [value] is `null`, removes the parameter entirely. If [name] already
     * exists in the query string, the existing values are replaced (single value
     * convention — pagination params are not multi-valued).
     */
    fun withQueryParam(
        request: Request,
        name: String,
        value: String?,
    ): Request {
        val newUrl = setQueryParam(request.url, name, value)
        return request.newBuilder().url(newUrl).build()
    }

    /**
     * Returns a new [Request] cloned from [request] but with [newUrl] as the URL. Headers,
     * method, and body are preserved from the original request.
     *
     * Used by strategies that follow an absolute next-page URL (e.g. `Link` header) — the
     * server has already encoded the paging state into the URL, so the rebuilder just
     * swaps the target while keeping all client-side context (auth headers, accept type,
     * idempotency key, etc.) intact.
     */
    fun withUrl(
        request: Request,
        newUrl: URL,
    ): Request = request.newBuilder().url(newUrl).build()

    /**
     * Returns a copy of [url] with the query parameter [name] set to [value]. Passing
     * `value = null` removes the parameter. Parameter order is preserved, except that an
     * added parameter is appended at the end.
     */
    fun setQueryParam(
        url: URL,
        name: String,
        value: String?,
    ): URL {
        val encodedName = URLEncoder.encode(name, UTF_8)
        val existing = url.query
        val outParams: MutableList<String> = ArrayList()
        var replaced = false
        if (!existing.isNullOrEmpty()) {
            for (segment in existing.split('&')) {
                if (segment.isEmpty()) continue
                val key = segment.substringBefore('=', segment)
                if (decodeOrRaw(key) == name) {
                    if (!replaced && value != null) {
                        outParams.add(encodedName + "=" + URLEncoder.encode(value, UTF_8))
                        replaced = true
                    }
                    // else: dropping this param (value == null) or already replaced once.
                } else {
                    outParams.add(segment)
                }
            }
        }
        if (!replaced && value != null) {
            outParams.add(encodedName + "=" + URLEncoder.encode(value, UTF_8))
        }
        val newQuery: String? = if (outParams.isEmpty()) null else outParams.joinToString("&")
        return rebuildUrl(url, newQuery)
    }

    /**
     * Returns the value of the query parameter [name] from [url], or `null` if absent.
     * The value is URL-decoded with UTF-8.
     */
    fun getQueryParam(
        url: URL,
        name: String,
    ): String? {
        val existing = url.query ?: return null
        if (existing.isEmpty()) return null
        for (segment in existing.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            val rawKey = if (eq >= 0) segment.substring(0, eq) else segment
            if (decodeOrRaw(rawKey) == name) {
                val rawValue = if (eq >= 0) segment.substring(eq + 1) else ""
                return decodeOrRaw(rawValue)
            }
        }
        return null
    }

    private fun decodeOrRaw(raw: String): String =
        try {
            URLDecoder.decode(raw, UTF_8)
        } catch (e: IllegalArgumentException) {
            // Malformed percent-encoding — return raw so equality with caller's name still works
            // for unencoded ASCII identifiers (the common case for pagination params).
            // We intentionally swallow `e` here; pagination should not fail on malformed legacy
            // URLs that the transport accepted.
            @Suppress("UNUSED_VARIABLE")
            val ignored = e
            raw
        }

    private fun rebuildUrl(
        source: URL,
        query: String?,
    ): URL {
        val sb = StringBuilder()
        sb.append(source.protocol).append("://")
        val userInfo = source.userInfo
        if (!userInfo.isNullOrEmpty()) {
            sb.append(userInfo).append('@')
        }
        sb.append(source.host)
        val port = source.port
        if (port >= 0) {
            sb.append(':').append(port)
        }
        val path = source.path ?: ""
        sb.append(path)
        if (!query.isNullOrEmpty()) {
            sb.append('?').append(query)
        }
        val ref = source.ref
        if (!ref.isNullOrEmpty()) {
            sb.append('#').append(ref)
        }
        return URL(sb.toString())
    }
}
