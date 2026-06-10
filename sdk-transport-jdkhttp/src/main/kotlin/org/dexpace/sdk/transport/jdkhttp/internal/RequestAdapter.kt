/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.instrumentation.ClientLogger
import java.net.http.HttpRequest
import java.time.Duration
import org.dexpace.sdk.core.http.request.Request as SdkRequest

/**
 * Adapts an SDK [SdkRequest] into a JDK [HttpRequest].
 *
 * Adaptation steps:
 *  1. URL: `request.url.toURI()` → `HttpRequest.Builder.uri(URI)`. The SDK already normalises
 *     the URL on construction, so the JDK receives a known-good [java.net.URI].
 *  2. Method + body: the body is adapted via [BodyPublishers] then attached with the
 *     appropriate method call on the JDK builder. `GET` / `HEAD` cannot carry a body in
 *     the JDK contract; the adapter forces `BodyPublishers.noBody()` for those.
 *  3. Headers: every name/value pair is copied across **except** entries named in
 *     [RestrictedHeaders] — those are silently dropped (with a DEBUG log) because the JDK
 *     client either computes them itself or rejects them outright.
 *  4. Per-request response timeout: applied via `HttpRequest.Builder.timeout(Duration)`
 *     when non-null. JDK 11+ enforces the timeout from connect through response-headers
 *     receipt.
 *
 * Stateless — the [logger] is the only field, used to attribute the DEBUG log naming dropped
 * headers to the transport.
 */
internal class RequestAdapter(
    private val logger: ClientLogger,
) {
    /**
     * Adapts [request] into a JDK [HttpRequest]. [responseTimeout] is applied per-request via
     * `HttpRequest.Builder.timeout(...)` when non-null.
     *
     * @throws IllegalArgumentException if [request.method] is [Method.CONNECT] — the JDK
     *         client reserves `CONNECT` for internal tunneling and rejects user-driven
     *         CONNECT exchanges. The SDK pipeline should not be issuing CONNECT requests
     *         directly; if proxy tunneling is required, configure a proxy on the underlying
     *         [java.net.http.HttpClient] instead.
     */
    fun adapt(
        request: SdkRequest,
        responseTimeout: Duration?,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder().uri(request.url.toURI())
        attachMethod(builder, request)
        attachHeaders(builder, request)
        responseTimeout?.let { builder.timeout(it) }
        return builder.build()
    }

    /**
     * Maps the SDK [Method] onto the JDK builder's method API. The split between methods
     * that take a [HttpRequest.BodyPublisher] and those that force [HttpRequest.BodyPublishers.noBody]
     * reflects the HTTP semantics enforced by the JDK builder:
     *
     *  - `GET` / `HEAD` / `TRACE` — no body. The JDK throws if a non-noBody publisher is supplied
     *    on `HEAD`, so the adapter sends `noBody()` for these methods regardless of what the SDK
     *    request carried. Callers passing a body to a GET/HEAD/TRACE request will see the body
     *    silently dropped here.
     *  - `POST` / `PUT` / `PATCH` / `DELETE` / `OPTIONS` — body publisher passed through. `DELETE`
     *    and `OPTIONS` with a body are unusual but permitted by HTTP and the JDK builder.
     *  - `CONNECT` — rejected; see [adapt]'s KDoc.
     */
    private fun attachMethod(
        builder: HttpRequest.Builder,
        request: SdkRequest,
    ) {
        val publisher = BodyPublishers.adaptBody(request.body)
        when (request.method) {
            Method.GET -> builder.GET()
            Method.HEAD -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
            Method.TRACE -> builder.method("TRACE", HttpRequest.BodyPublishers.noBody())
            Method.POST -> builder.POST(publisher)
            Method.PUT -> builder.PUT(publisher)
            Method.DELETE -> builder.method("DELETE", publisher)
            Method.PATCH -> builder.method("PATCH", publisher)
            Method.OPTIONS -> builder.method("OPTIONS", publisher)
            Method.CONNECT -> throw IllegalArgumentException(
                "java.net.http.HttpClient does not support user-issued CONNECT requests. " +
                    "Configure a proxy on the underlying HttpClient instead.",
            )
        }
    }

    /**
     * Copies headers from the SDK request to the JDK builder. Entries named in
     * [RestrictedHeaders] are skipped with a DEBUG log; pass-through values are
     * `header`'d individually so multi-value semantics are preserved on the wire.
     *
     * Each `builder.header(...)` call is wrapped in a `try/catch(IllegalArgumentException)`:
     * the JDK's disallowed-header set is version-dependent and may grow, so a header that
     * [RestrictedHeaders] doesn't anticipate could still be rejected by `HttpRequest.Builder`.
     * Catching it here drops the offending header with a DEBUG log rather than letting the
     * `IllegalArgumentException` escape [adapt] (and therefore `execute`, declared
     * `@Throws(IOException)`) where a caller's `catch(IOException)` would not observe it.
     *
     * Note this catch guards against the JDK's restricted *name* set only. Illegal header
     * *values* (CR/LF and similar) are now rejected upstream by `Headers.Builder`, so a value
     * with control characters never reaches this point — the `IllegalArgumentException` handled
     * here is the JDK refusing a restricted name, not a malformed value.
     */
    private fun attachHeaders(
        builder: HttpRequest.Builder,
        request: SdkRequest,
    ) {
        for ((rawName, values) in request.headers.entries()) {
            if (RestrictedHeaders.isRestricted(rawName)) {
                logger.atVerbose()
                    .event("transport.jdkhttp.header.dropped")
                    .field("name", rawName)
                    .log("dropping restricted header before dispatch")
                continue
            }
            for (value in values) {
                try {
                    builder.header(rawName, value)
                } catch (e: IllegalArgumentException) {
                    logger.atVerbose()
                        .event("transport.jdkhttp.header.rejected")
                        .field("name", rawName)
                        .cause(e)
                        .log("JDK rejected header value; dropping before dispatch")
                }
            }
        }
    }
}
