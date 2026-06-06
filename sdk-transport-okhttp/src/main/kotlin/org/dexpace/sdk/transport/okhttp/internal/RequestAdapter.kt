/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import okhttp3.Request
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.http.request.Request as SdkRequest

/**
 * Adapts an SDK [SdkRequest] into OkHttp's [Request].
 *
 * Adaptation steps:
 *  1. URL: `request.url.toExternalForm()` → `Request.Builder.url(String)`. The SDK already
 *     normalises the URL on construction, so OkHttp receives a known-good URL string.
 *  2. Headers: every name/value pair is copied across **except** entries named in
 *     [RestrictedHeaders] — those are silently dropped (with a DEBUG log) because OkHttp
 *     computes them from the body / connection.
 *  3. Method + body: the SDK body is wrapped in [SdkRequestBodyAdapter] when present.
 *     Methods that require a body (POST/PUT/PATCH/DELETE) pass the adapter; methods
 *     without one (GET/HEAD/OPTIONS/TRACE) pass `null` per OkHttp's contract.
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
                builder.addHeader(rawName, value)
            }
        }
        val okhttpBody = request.body?.let { SdkRequestBodyAdapter(it) }
        builder.method(request.method.method, okhttpBody)
        return builder.build()
    }

    /**
     * OkHttp accepts any token as the method name; the SDK enum is restricted to canonical
     * HTTP methods. Exposed for completeness — call sites use [Method.method] directly.
     */
    fun methodName(method: Method): String = method.method
}
