/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.PercentEncoding
import org.dexpace.sdk.core.http.request.Request

/**
 * Assembles a [Request] from an [OperationParams] projection and a base URL. Internal — callers
 * go through [OperationParams.toRequest].
 *
 * The path template is resolved by substituting each `{name}` with its percent-encoded path
 * parameter; the query is rendered by [org.dexpace.sdk.core.http.common.QueryParams.encode]; and
 * the base URL is treated as a verbatim prefix (one `/` between it and the resolved path), so the
 * scheme/host/port/base-path of [baseUrl] carry through unchanged.
 */
internal object OperationRequestAssembler {
    private val TEMPLATE_VARIABLE = Regex("""\{([^/}]+)}""")

    internal fun assemble(
        baseUrl: String,
        params: OperationParams,
    ): Request {
        val path = resolvePath(params.pathTemplate, params.pathParams())
        val query = params.queryParams().encode()
        return Request.builder()
            .method(params.method)
            .url(buildUrl(baseUrl, path, query))
            .headers(params.headers())
            .body(params.body())
            .build()
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
        val sb = StringBuilder(baseUrl.trimEnd('/'))
        if (path.isNotEmpty()) {
            if (!path.startsWith("/")) sb.append('/')
            sb.append(path)
        }
        if (query.isNotEmpty()) {
            sb.append('?').append(query)
        }
        return sb.toString()
    }
}
