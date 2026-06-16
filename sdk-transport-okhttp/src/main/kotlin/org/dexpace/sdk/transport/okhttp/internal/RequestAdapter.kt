/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.dexpace.sdk.core.http.request.FileRequestBody
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.http.request.Request as SdkRequest
import org.dexpace.sdk.core.http.request.RequestBody as SdkRequestBody

/**
 * Adapts an SDK [SdkRequest] into OkHttp's [Request].
 *
 * Adaptation steps:
 *  1. URL: `request.url.toExternalForm()` → `Request.Builder.url(String)`. The SDK already
 *     normalises the URL on construction, so OkHttp receives a known-good URL string.
 *  2. Headers: every name/value pair is copied across **except** entries named in
 *     [RestrictedHeaders] — those are silently dropped (with a DEBUG log) because OkHttp
 *     computes them from the body / connection.
 *  3. Method + body: a [FileRequestBody] is wrapped in [FileRequestBodyAdapter] so the
 *     bytes stream zero-copy through okio's [okio.FileHandle]; any other SDK body is wrapped
 *     in the generic [SdkRequestBodyAdapter]. When the SDK request carries no body, the
 *     adapter normally passes `null`, but OkHttp's `Request.Builder.method` rejects a null
 *     body for POST/PUT/PATCH (the methods OkHttp treats as requiring one). The SDK model
 *     makes a body optional for every method, so a body-less POST is a valid request; to keep
 *     it from crashing with `IllegalArgumentException`, the adapter substitutes a zero-length
 *     body for those methods (see [requiresRequestBody]). GET/HEAD/OPTIONS/TRACE/DELETE keep
 *     their `null` body.
 *
 * The adapter is stateless — the [logger] is the only field it carries so the DEBUG log
 * naming dropped headers attributes to the transport.
 */
internal class RequestAdapter(
    private val logger: ClientLogger,
) {
    fun adapt(request: SdkRequest): Request {
        val builder = Request.Builder().url(request.url.toExternalForm())
        for ((rawName, values) in request.headers.entries()) {
            if (RestrictedHeaders.isRestricted(rawName)) {
                logger.atVerbose()
                    .event("transport.okhttp.header.dropped")
                    .field("name", rawName)
                    .log("dropping restricted header before dispatch")
                continue
            }
            for (value in values) {
                // Header names/values are validated upstream by Headers.Builder (CR/LF and other
                // illegal characters are rejected at construction), so addHeader receives only
                // well-formed values here.
                builder.addHeader(rawName, value)
            }
        }
        val methodToken = request.method.method
        val okhttpBody =
            when {
                request.body != null -> toOkHttpBody(request.body!!)
                // OkHttp rejects a null body for the methods it treats as requiring one. The
                // SDK allows a body-less request for any method, so substitute an empty body
                // here rather than letting Request.Builder.method throw.
                requiresRequestBody(methodToken) -> EMPTY_BODY
                else -> null
            }
        builder.method(methodToken, okhttpBody)
        return builder.build()
    }

    /**
     * Whether OkHttp's `Request.Builder.method` rejects a null body for [methodToken]. OkHttp's
     * own set is `{POST, PUT, PATCH, PROPPATCH, REPORT}`; the SDK's [org.dexpace.sdk.core.http.request.Method]
     * enum cannot produce the two WebDAV tokens, so the reachable set is exactly POST/PUT/PATCH.
     * An explicit check is used instead of OkHttp's `okhttp3.internal.http.HttpMethod` because
     * that is an `internal` Kotlin symbol — depending on it couples this transport to an
     * unstable cross-module API for no real gain over a three-token comparison.
     */
    private fun requiresRequestBody(methodToken: String): Boolean =
        methodToken == "POST" || methodToken == "PUT" || methodToken == "PATCH"

    /**
     * Selects the OkHttp [RequestBody] adapter for an SDK body. A [FileRequestBody] streams
     * zero-copy via [FileRequestBodyAdapter] (okio `FileHandle` → OkHttp's `BufferedSink`,
     * honouring `position`/`count`); every other body shape uses the generic
     * [SdkRequestBodyAdapter].
     */
    private fun toOkHttpBody(body: SdkRequestBody): RequestBody =
        when (body) {
            is FileRequestBody -> FileRequestBodyAdapter(body)
            else -> SdkRequestBodyAdapter(body)
        }

    private companion object {
        /**
         * Shared zero-length body substituted for a body-less POST/PUT/PATCH. Immutable and
         * stateless, so a single instance is safe to reuse across requests and threads.
         */
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)
    }
}
