/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.DroppedHeaderLogging
import org.dexpace.sdk.core.instrumentation.LogLevel
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 * Carries the [logger] (to attribute the dropped-header log to the transport) and the
 * [droppedHeaderLogging] policy with its per-header WARN-dedup state; otherwise stateless and safe
 * to share across requests and threads.
 */
internal class RequestAdapter(
    private val logger: ClientLogger,
    private val droppedHeaderLogging: DroppedHeaderLogging = DroppedHeaderLogging.ONCE_PER_HEADER,
) {
    // Header names already WARN-logged under ONCE_PER_HEADER, keyed by lower-cased name so a re-set
    // of the same header (any casing) warns at most once per transport. Thread-safe: an async
    // transport adapts requests on many threads.
    private val warnedDroppedHeaders: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether this drop of [rawName] should log at `WARN` (else verbose), per [droppedHeaderLogging].
     * Internal for direct policy testing: the transport test modules run on `slf4j-nop` and cannot
     * capture emitted log output, so the dedup decision is asserted here rather than through logs.
     *
     * Callers MUST gate this behind `logger.canLog(LogLevel.WARNING)` (see [attachHeaders]): under
     * `ONCE_PER_HEADER` the call has the side effect of recording [rawName], so spending it while
     * `WARN` is disabled would consume the one-shot without emitting anything and hide the drop if
     * the level is later raised. The `ONCE_PER_HEADER` set is bounded at [MAX_WARNED_DROPPED_HEADERS]
     * entries so a caller synthesising unbounded distinct non-token names cannot grow it without
     * limit on a transport-lifetime adapter; past the bound, further first-drops log at verbose.
     */
    internal fun shouldWarnForDroppedHeader(rawName: String): Boolean =
        when (droppedHeaderLogging) {
            DroppedHeaderLogging.PER_OCCURRENCE -> true
            DroppedHeaderLogging.ONCE_PER_HEADER ->
                warnedDroppedHeaders.size < MAX_WARNED_DROPPED_HEADERS &&
                    warnedDroppedHeaders.add(rawName.lowercase(Locale.US))
            DroppedHeaderLogging.VERBOSE_ONLY -> false
        }

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
        attachDerivedContentType(builder, request)
        responseTimeout?.let { builder.timeout(it) }
        return builder.build()
    }

    /**
     * Emits a `Content-Type` derived from the request body's media type when — and only when — the
     * caller set no explicit `Content-Type` header.
     *
     * `java.net.http.HttpClient` does not derive a `Content-Type` from the body publisher, unlike
     * OkHttp's `BridgeInterceptor`. So the documented `RequestBody.create(value, serde)` pattern —
     * which auto-stamps the body with `application/json` — would put a `Content-Type` on the wire
     * through the OkHttp transport but none through this one. Deriving it here from
     * [org.dexpace.sdk.core.http.request.RequestBody.mediaType] closes that gap so both transports
     * agree on the wire form.
     *
     * An explicit caller `Content-Type` (already copied by [attachHeaders]; `Content-Type` is not a
     * [RestrictedHeaders] entry) is authoritative and never overridden — the `headers.contains`
     * check is case-insensitive, so any casing of the caller's header suppresses the derived one.
     */
    private fun attachDerivedContentType(
        builder: HttpRequest.Builder,
        request: SdkRequest,
    ) {
        if (request.headers.contains("Content-Type")) {
            return
        }
        val mediaType = request.body?.mediaType() ?: return
        try {
            builder.header("Content-Type", mediaType.toString())
        } catch (e: IllegalArgumentException) {
            // Defensive: a body media type the JDK refuses as a header value. Drop it rather than
            // let the IllegalArgumentException escape adapt (reachable from execute, declared
            // @Throws(IOException)); the request proceeds without a derived Content-Type.
            logger.atVerbose()
                .event("transport.jdkhttp.header.rejected")
                .field("name", "Content-Type")
                .cause(e)
                .log("JDK rejected body-derived Content-Type; sending without it")
        }
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
     *
     * **Bodyless-DELETE wire divergence (residual, documented).** For a bodyless DELETE the SDK body
     * is `null`, so [BodyPublishers.adaptBody] returns `HttpRequest.BodyPublishers.noBody()`, whose
     * `contentLength()` is `0`. On JDK builds that serialize a fixed zero length, this puts
     * `Content-Length: 0` on the wire, whereas the OkHttp transport sends a bodyless DELETE with no
     * `Content-Length` at all (its `null`-body path writes no framing header). The `java.net.http`
     * builder API cannot express a non-GET method with a genuinely absent body — `method(String,
     * BodyPublisher)` requires a non-null publisher and the `DELETE()` convenience itself uses
     * `noBody()` — so `noBody()` is the only representable "empty" body and this residual difference
     * cannot be eliminated at the adapter layer. It is a header-only divergence with no effect on the
     * request semantics. (Bodyless POST/PUT/PATCH already emit `Content-Length: 0` on both transports
     * — OkHttp substitutes a zero-length body there — so only DELETE diverges.)
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
     * Upstream `Headers.Builder` validation restricts names and values to printable ASCII (control
     * characters and non-ASCII bytes are rejected at construction), closing the request/header-
     * splitting surface, but it does not mirror the JDK's full field-name/value token grammar. The
     * `IllegalArgumentException` caught here is therefore the JDK refusing either a name in its
     * restricted set or a model-valid but non-token name/value (e.g. one carrying an interior
     * space) — not a control-character splitting vector, which never gets this far.
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
                    // The model already rejects at construction the names/values no transport can
                    // encode (control + non-ASCII), so this residual drop is a model-valid but
                    // non-token header only this transport refuses. How loudly it surfaces is the
                    // caller's DroppedHeaderLogging choice; the default warns once per header name and
                    // logs the rest at verbose so a hot path re-setting it does not flood the log.
                    // Gate the dedup on canLog(WARNING) so its one-shot side effect is spent only when
                    // WARN can actually be emitted (see shouldWarnForDroppedHeader).
                    val warn = logger.canLog(LogLevel.WARNING) && shouldWarnForDroppedHeader(rawName)
                    val event = if (warn) logger.atWarning() else logger.atVerbose()
                    event
                        .event("transport.jdkhttp.header.rejected")
                        .field("name", rawName)
                        .cause(e)
                        .log("JDK rejected header name/value it cannot encode; dropping before dispatch")
                }
            }
        }
    }

    private companion object {
        /**
         * Upper bound on the `ONCE_PER_HEADER` dedup set. The adapter lives for the transport's
         * lifetime, so an unbounded set would leak one entry per distinct dropped header name; a
         * caller deriving names from request data could grow it without limit. Past this many
         * distinct names, further first-drops fall through to verbose rather than being recorded.
         */
        private const val MAX_WARNED_DROPPED_HEADERS: Int = 1024
    }
}
