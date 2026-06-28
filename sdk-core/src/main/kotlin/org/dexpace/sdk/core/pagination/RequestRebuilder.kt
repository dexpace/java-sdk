/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.PercentEncoding
import org.dexpace.sdk.core.http.common.QueryParams
import org.dexpace.sdk.core.http.request.Request
import java.net.URL

/**
 * Internal helpers for building the next-page [Request] from an initial [Request] plus a
 * paging-state mutation.
 *
 * The functions here are pure URL-rewriting utilities — no I/O and no mutation of the
 * supplied [Request]. They exist so pagination strategies can stay focused on extracting
 * paging state from responses, while the mechanical work of "produce a new request with this
 * query param set" lives in one place.
 *
 * ## Query manipulation
 *
 * Reading a parameter ([getQueryParam]) goes through the structured [QueryParams] model.
 * Writing one ([setQueryParam]) splices the raw query string directly — replacing or
 * appending only the targeted parameter and copying every other segment **verbatim** — so
 * parameters the caller did not touch keep their exact wire form (a value-less `?flag` stays
 * value-less; reserved characters are not rewritten). Newly written names and values are
 * percent-encoded with RFC 3986 semantics (space → `%20`) via [PercentEncoding].
 *
 * [QueryParams.encode] is deliberately *not* used to reassemble the whole query: it re-renders
 * every parameter in canonical form, which would alter untouched segments. This editor favours
 * fidelity over funnelling everything through the model — see the note on [QueryParams].
 *
 * ## URL.fragment / userInfo
 *
 * The rebuilder preserves the URL's `userInfo`, `host`, `port`, `path`, and `ref`
 * (fragment) exactly. Only the query string is mutated.
 */
internal object RequestRebuilder {
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
        val existing = url.query
        val outParams: MutableList<String> = ArrayList()
        var replaced = false
        if (!existing.isNullOrEmpty()) {
            for (segment in existing.split('&')) {
                if (segment.isEmpty()) continue
                val key = segment.substringBefore('=', segment)
                if (PercentEncoding.decodeComponent(key) == name) {
                    if (!replaced && value != null) {
                        outParams.add(encodeParam(name, value))
                        replaced = true
                    }
                    // else: dropping this param (value == null) or already replaced once.
                } else {
                    // Untouched parameter — copy its raw segment byte-for-byte.
                    outParams.add(segment)
                }
            }
        }
        if (!replaced && value != null) {
            outParams.add(encodeParam(name, value))
        }
        val newQuery: String? = if (outParams.isEmpty()) null else outParams.joinToString("&")
        return rebuildUrl(url, newQuery)
    }

    private fun encodeParam(
        name: String,
        value: String,
    ): String = PercentEncoding.encodeComponent(name) + "=" + PercentEncoding.encodeComponent(value)

    /**
     * Returns the value of the query parameter [name] from [url], or `null` if absent.
     * The value is decoded with RFC 3986 semantics (see [QueryParams.parse]).
     */
    fun getQueryParam(
        url: URL,
        name: String,
    ): String? = QueryParams.parse(url.query).get(name)

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
