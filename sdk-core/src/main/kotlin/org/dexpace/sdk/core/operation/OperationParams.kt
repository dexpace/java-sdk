/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.QueryParams
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.context.RequestContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody

/**
 * SPI that projects an operation's typed inputs onto an HTTP request.
 *
 * A thin generated service implements this once per operation, exposing where each input
 * belongs on the wire — **path**, **query**, **header**, or **body** — and the runtime
 * ([toRequest] / [toRequestContext]) assembles the [Request] and feeds it into the context
 * chain. This is the seam #57 defines so generated code never does URL string surgery:
 * cursors, page numbers, and operation arguments flow through the typed projections below
 * (and through [QueryParams] / [Headers]) rather than being spliced into a URL.
 *
 * Only [method] and [pathTemplate] are required; the four projections default to empty so a
 * parameterless operation overrides almost nothing:
 *
 * ```kotlin
 * class ListPets(private val limit: Int?) : OperationParams {
 *     override val method = Method.GET
 *     override val pathTemplate = "/pets"
 *     override fun queryParams() =
 *         QueryParams.builder().apply { limit?.let { set("limit", it.toString()) } }.build()
 * }
 *
 * val request = ListPets(limit = 20).toRequest("https://api.example.com")
 * ```
 *
 * Path parameters are substituted into [pathTemplate] (`/pets/{petId}`) and percent-encoded as
 * path segments, so a value cannot inject extra `/` segments. Query parameters are appended via
 * [QueryParams.encode] (RFC 3986). The body is whatever [RequestBody] the operation already
 * built (JSON via the serde seam, a form body, etc.) — this SPI carries it, it does not encode it.
 */
public interface OperationParams {
    /** The HTTP method for this operation. */
    public val method: Method

    /**
     * The path template, relative to the base URL, with `{name}` placeholders for path
     * parameters — e.g. `/pets/{petId}`. A leading `/` is optional.
     */
    public val pathTemplate: String

    /**
     * A stable operation identifier (e.g. `"ListPets"`) for instrumentation, or `null`.
     * [toRequestContext] carries it onto the [RequestContext] (and through to the
     * [org.dexpace.sdk.core.http.context.ExchangeContext]) so the tracing seam can label the
     * operation; it does **not** affect the assembled request's URL, headers, or body.
     */
    public val operationName: String? get() = null

    /**
     * Decoded path-parameter values keyed by placeholder name. Every `{name}` in [pathTemplate]
     * must have an entry; values are percent-encoded as path segments during assembly.
     */
    public fun pathParams(): Map<String, String> = emptyMap()

    /** Query parameters to append to the URL. */
    public fun queryParams(): QueryParams = QueryParams.empty()

    /** Headers to set on the request. */
    public fun headers(): Headers = Headers.builder().build()

    /** The request body, or `null` for a bodyless operation. */
    public fun body(): RequestBody? = null

    /**
     * Assembles the [Request] for this operation against [baseUrl] (e.g.
     * `https://api.example.com` or `https://api.example.com/v1`). A trailing `/` on [baseUrl] is
     * trimmed before the resolved path is joined. If [baseUrl] already carries a query (e.g. a
     * signed/SAS base such as `https://host/c?sig=…`), the resolved path is inserted before it and
     * this operation's query is appended after it.
     *
     * @throws IllegalArgumentException if a `{name}` in [pathTemplate] has no [pathParams] value,
     *   if [baseUrl] carries a fragment (`#…`), or if [baseUrl] resolves to a malformed URL
     *   (e.g. it has no scheme).
     */
    public fun toRequest(baseUrl: String): Request = OperationRequestAssembler.assemble(baseUrl, this)

    /**
     * Assembles the [Request] ([toRequest]) and promotes [dispatch] into a [RequestContext]
     * carrying it — feeding the request into the context chain in one step. [operationName] is
     * carried onto the resulting context for the tracing seam.
     */
    public fun toRequestContext(
        baseUrl: String,
        dispatch: DispatchContext,
    ): RequestContext = dispatch.toRequestContext(toRequest(baseUrl), operationName)
}
