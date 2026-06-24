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
     *  - `GET` / `HEAD` / `TRACE` — no body. [Method.permitsRequestBody] is `false` for these (and
     *    for `CONNECT`, which is rejected outright in its own branch below), so
     *    `Request.RequestBuilder.build` rejects a body on them at construction; an SDK request can
     *    never carry one here. The adapter sends `noBody()` unconditionally rather than adapting
     *    `request.body` — there is nothing to adapt, and `noBody()` consumes nothing (the previous
     *    code adapted then discarded the publisher, which for a small body drained a consume-once
     *    `writeTo` for nothing).
     *  - `POST` / `PUT` / `PATCH` / `DELETE` / `OPTIONS` — body publisher passed through. `DELETE`
     *    and `OPTIONS` with a body are unusual but permitted by HTTP and the JDK builder.
     *  - `CONNECT` — rejected; see [adapt]'s KDoc.
     */
    private fun attachMethod(
        builder: HttpRequest.Builder,
        request: SdkRequest,
    ) {
        when (request.method) {
            Method.GET -> builder.GET()
            Method.HEAD -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
            Method.TRACE -> builder.method("TRACE", HttpRequest.BodyPublishers.noBody())
            Method.POST -> builder.POST(BodyPublishers.adaptBody(request.body))
            Method.PUT -> builder.PUT(BodyPublishers.adaptBody(request.body))
            Method.DELETE -> builder.method("DELETE", BodyPublishers.adaptBody(request.body))
            Method.PATCH -> builder.method("PATCH", BodyPublishers.adaptBody(request.body))
            Method.OPTIONS -> builder.method("OPTIONS", BodyPublishers.adaptBody(request.body))
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
     * Upstream `Headers.Builder` validation closes the request/header-splitting surface
     * (control-character names, and control-character values bar horizontal tab, are rejected
     * before they reach here), but it does not mirror the JDK's full field-name/value grammar.
     * The `IllegalArgumentException` caught
     * here is therefore the JDK refusing either a name in its restricted set or a model-valid
     * name/value it nonetheless rejects (e.g. a non-token / non-ASCII byte the SDK deliberately
     * permits) — not a control-character splitting vector, which never gets this far.
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
                    // Warn (not verbose): this is a header the caller explicitly set being silently
                    // dropped because this transport cannot encode it — surfaced by default so the
                    // loss is visible. Restricted-header drops above stay at verbose (expected, the
                    // JDK recomputes or forbids them), as does the inbound response-header drop.
                    logger.atWarning()
                        .event("transport.jdkhttp.header.rejected")
                        .field("name", rawName)
                        .cause(e)
                        .log("JDK rejected header name/value; dropping before dispatch")
                }
            }
        }
    }
}
