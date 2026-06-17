/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.context.RequestContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Materializes an [OperationParams] projection into a concrete [Request].
 *
 * This is the runtime primitive that bridges the operation SPI to the context chain: given a
 * base URL, an HTTP [Method], a path template, and the operation's [OperationParams], it
 * substitutes path placeholders, appends the query string, and stamps headers and body onto a
 * fresh [Request.RequestBuilder]. The resulting [Request] enters the
 * `DispatchContext -> RequestContext -> ExchangeContext` promotion chain unchanged.
 *
 * Encoding is owned here: [PathParam] values are percent-encoded for a path segment (so `/` in a
 * value is escaped and does not introduce a new segment), and [QueryParam] names/values are
 * `application/x-www-form-urlencoded`-encoded with `+` rewritten to `%20` so a literal space is
 * not silently turned into a plus. Callers therefore supply raw, unencoded values.
 *
 * Stateless and thread-safe.
 */
public object RequestProjector {
    private const val SPACE_PLUS = "+"
    private const val SPACE_PCT = "%20"

    /**
     * Projects [params] onto [baseUrl] for an operation with the given [method] and
     * [pathTemplate], returning the request the SDK will dispatch.
     *
     * The [pathTemplate] is resolved against [baseUrl] (it may be absolute or, more typically, a
     * relative path with `{name}` placeholders); each placeholder is replaced by the matching
     * [OperationParams.pathParams] entry. The query string from [OperationParams.queryParams] is
     * appended, then [OperationParams.headers] and [OperationParams.body] are applied.
     *
     * @param baseUrl The client base URL the [pathTemplate] resolves against.
     * @param method The HTTP method for this operation.
     * @param pathTemplate The operation path, with optional `{name}` placeholders.
     * @param params The operation's projection.
     * @return The materialized request.
     * @throws IllegalArgumentException If a `{name}` placeholder has no matching path param.
     */
    @JvmStatic
    public fun project(
        baseUrl: URL,
        method: Method,
        pathTemplate: String,
        params: OperationParams,
    ): Request {
        val resolvedPath = substitutePath(pathTemplate, params.pathParams())
        val query = encodeQuery(params.queryParams())
        val spec = if (query.isEmpty()) resolvedPath else "$resolvedPath?$query"
        val url = URL(baseUrl, spec)

        val builder =
            Request.builder()
                .method(method)
                .url(url)
                .headers(params.headers())

        params.body()?.let(builder::body)
        return builder.build()
    }

    /**
     * Projects [params] (see [project]) and promotes [dispatch] into the resulting
     * [org.dexpace.sdk.core.http.context.RequestContext], registering it on the chain's store
     * slot. This is the single call a thin service makes to turn an operation's inputs into a
     * live request context: the [dispatch]'s instrumentation, call key, and per-call options are
     * all carried forward by [org.dexpace.sdk.core.http.context.DispatchContext.toRequestContext].
     *
     * @return The promoted request context bound to the projected request.
     * @throws IllegalArgumentException If a `{name}` placeholder has no matching path param.
     */
    @JvmStatic
    public fun projectInto(
        dispatch: DispatchContext,
        baseUrl: URL,
        method: Method,
        pathTemplate: String,
        params: OperationParams,
    ): RequestContext = dispatch.toRequestContext(project(baseUrl, method, pathTemplate, params))

    private fun substitutePath(
        pathTemplate: String,
        pathParams: List<PathParam>,
    ): String {
        var result = pathTemplate
        for (param in pathParams) {
            val placeholder = "{" + param.name + "}"
            require(result.contains(placeholder)) {
                "path template '$pathTemplate' has no placeholder for path param '${param.name}'"
            }
            result = result.replace(placeholder, encodePathSegment(param.value))
        }
        require(!hasUnresolvedPlaceholder(result)) {
            "path template '$pathTemplate' has unresolved placeholders after substitution: '$result'"
        }
        return result
    }

    /**
     * Detects a leftover `{name}` placeholder. A bare `{` or `}` (legal in a URL) is not flagged;
     * only a `{...}` pair with no slash between the braces counts as an unresolved placeholder.
     */
    private fun hasUnresolvedPlaceholder(path: String): Boolean {
        val open = path.indexOf('{')
        if (open < 0) return false
        val close = path.indexOf('}', open + 1)
        if (close < 0) return false
        return !path.substring(open + 1, close).contains('/')
    }

    private fun encodeQuery(queryParams: List<QueryParam>): String {
        if (queryParams.isEmpty()) return ""
        return queryParams.joinToString("&") { param ->
            val name = encodeFormComponent(param.name)
            when (val value = param.value) {
                null -> name
                else -> name + "=" + encodeFormComponent(value)
            }
        }
    }

    /**
     * Percent-encode a single path segment value. [encodeFormComponent] already escapes `/`
     * (as `%2F`) so an embedded slash cannot introduce a new segment, and rewrites the
     * form-encoder's `+`-for-space to `%20`, which is what a path segment requires.
     */
    private fun encodePathSegment(value: String): String = encodeFormComponent(value)

    /**
     * `application/x-www-form-urlencoded` encode, then rewrite `+` to `%20` so a literal space
     * round-trips as a space rather than a plus. `URLEncoder` is the only JDK 8 primitive for this.
     */
    private fun encodeFormComponent(raw: String): String =
        URLEncoder
            .encode(raw, StandardCharsets.UTF_8.name())
            .replace(SPACE_PLUS, SPACE_PCT)
}
