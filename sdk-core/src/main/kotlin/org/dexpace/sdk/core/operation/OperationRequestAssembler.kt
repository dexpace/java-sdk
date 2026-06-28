/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.PercentEncoding
import org.dexpace.sdk.core.http.request.Request
import java.net.MalformedURLException

/**
 * Assembles a [Request] from an [OperationParams] projection and a base URL. Internal — callers
 * go through [OperationParams.toRequest].
 *
 * The path template is resolved by substituting each `{name}` with its percent-encoded path
 * parameter; the query is rendered by [org.dexpace.sdk.core.http.common.QueryParams.encode]; and
 * the base URL's scheme/host/port/base-path carry through unchanged. The resolved path is inserted
 * after the base path but **before** any query the base URL already carries (e.g. a signed/SAS
 * base such as `https://host/c?sig=…`), and the operation's query is appended after the base
 * query. A fragment on the base URL is rejected — it cannot be composed with a path/query and is
 * never sent on the wire, so it is almost certainly a mistake.
 */
internal object OperationRequestAssembler {
    private val TEMPLATE_VARIABLE = Regex("""\{([^/}]+)}""")

    internal fun assemble(
        baseUrl: String,
        params: OperationParams,
    ): Request {
        val path = resolvePath(params.pathTemplate, params.pathParams())
        val query = params.queryParams().encode()
        val url = buildUrl(baseUrl, path, query)
        return try {
            Request.builder()
                .method(params.method)
                .url(url)
                .headers(params.headers())
                .body(params.body())
                .build()
        } catch (e: MalformedURLException) {
            // Surface a malformed base URL (e.g. missing scheme) as the SPI's documented
            // IllegalArgumentException rather than leaking a checked IOException to callers.
            throw IllegalArgumentException(
                "Cannot assemble a request: base URL '$baseUrl' resolved to a malformed URL '$url'",
                e,
            )
        }
    }

    private fun resolvePath(
        template: String,
        pathParams: Map<String, String>,
    ): String =
        TEMPLATE_VARIABLE.replace(template) { match ->
            val name = match.groupValues[1]
            val value =
                pathParams[name]
                    ?: throw IllegalArgumentException("Missing path parameter '$name' for template '$template'")
            PercentEncoding.encodeComponent(value)
        }

    private fun buildUrl(
        baseUrl: String,
        path: String,
        query: String,
    ): String {
        require('#' !in baseUrl) {
            "Base URL must not contain a fragment ('#'): '$baseUrl'"
        }
        // Split off any query the base URL already carries so the resolved path is inserted
        // before it (`host/base?sig=…` + `/pets` → `host/base/pets?sig=…`), instead of being
        // swallowed into a query value.
        val queryStart = baseUrl.indexOf('?')
        val prefix = if (queryStart < 0) baseUrl else baseUrl.substring(0, queryStart)
        val baseQuery = if (queryStart < 0) "" else baseUrl.substring(queryStart + 1)

        val sb = StringBuilder(prefix)
        // Join the resolved path with exactly one '/'. A trailing '/' on the base is only
        // significant when there is no path to join, so an empty path leaves the base untouched
        // (e.g. `https://host/v1/` stays `…/v1/`) rather than being silently stripped.
        if (path.isNotEmpty()) {
            val baseEndsWithSlash = prefix.endsWith('/')
            val pathStartsWithSlash = path.startsWith('/')
            if (baseEndsWithSlash && pathStartsWithSlash) {
                sb.setLength(sb.length - 1)
            } else if (!baseEndsWithSlash && !pathStartsWithSlash) {
                sb.append('/')
            }
            sb.append(path)
        }
        // Base query first (it is ambient — auth tokens, API keys), operation query appended.
        // Both sides are already percent-encoded wire form, so they concatenate verbatim; a
        // trailing '&' already on the base query is reused as the separator rather than doubled
        // into an empty `&&` segment.
        val mergedQuery =
            when {
                baseQuery.isEmpty() -> query
                query.isEmpty() -> baseQuery
                baseQuery.endsWith('&') -> baseQuery + query
                else -> "$baseQuery&$query"
            }
        if (mergedQuery.isNotEmpty()) {
            sb.append('?').append(mergedQuery)
        }
        return sb.toString()
    }
}
