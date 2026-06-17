/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.LoggingEvent
import org.dexpace.sdk.core.instrumentation.UrlRedactor
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.util.Clock

/**
 * Shared emit / redact / preview / metrics logic for the sync ([DefaultInstrumentationStep]) and
 * async ([DefaultAsyncInstrumentationStep]) instrumentation steps. Both steps own the same
 * request/response/failure event shape, header redaction, body wrapping, and metric instruments;
 * this class is the single home for that logic so the two steps differ only in how they thread the
 * downstream call (synchronous `process` vs. a `CompletableFuture` continuation).
 *
 * ## Body-size fields
 *
 * For each captured body the emitted event carries three size-related fields:
 *
 * - `*.body.size` — the size of the **captured preview** (`min(actualSize, bodyPreviewMaxBytes)`).
 *   Preserved for backwards compatibility with existing dashboards.
 * - `*.body.actual_size` — the body's **true** size when it is known (the request body's declared
 *   `contentLength()`, or the response body's full length); omitted when the size is unknown
 *   (a streaming / chunked body with `contentLength() < 0`).
 * - `*.body.preview_truncated` — `true` when the preview is only a prefix of a larger body, so a
 *   consumer can tell an 8 KiB body from an 8 GB one without reading the bytes.
 *
 * ## Unknown-length response bodies
 *
 * [wrapResponseForLogging] skips body capture entirely for an unknown-length
 * (`contentLength() < 0`) response body. The bounded drain would otherwise block the draining
 * thread — the caller's thread in the sync step, the future-completion thread in the async step —
 * on a slow / idle producer (SSE, long-poll, chunked trickle), stalling time-to-first-byte. Such a
 * body streams to the caller unwrapped, so the `http.response` event still carries headers, status,
 * and `response.content.length = -1`, but no body preview.
 *
 * ## Best-effort logging
 *
 * Every emit method catches its own failures and re-emits them as an
 * `http.instrumentation.*` warning — a logging failure never fails the request.
 *
 * Stateless after construction (the meter instruments are reused). Safe to share across concurrent
 * requests.
 */
internal class InstrumentationEmitters(
    private val options: HttpInstrumentationOptions,
    private val clock: Clock,
    private val logger: ClientLogger,
) {
    // Lazily constructed so a step instance never installed in a pipeline doesn't pay
    // the counter/histogram registration cost. `PUBLICATION` mode avoids the synchronized
    // block that the default `SYNCHRONIZED` mode uses for first-read coordination — that
    // monitor would pin a virtual-thread carrier during init (see CLAUDE.md "ReentrantLock
    // over synchronized"). With OTel meter implementations being idempotent on register-by-
    // name, racing initializers cost at most one redundant registration that is discarded.
    private val requestCounter: LongCounter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        options.meter.counter(
            name = "http.client.request.count",
            description = "Total HTTP requests sent through this pipeline.",
            unit = "{request}",
        )
    }
    private val latencyHistogram: DoubleHistogram by lazy(LazyThreadSafetyMode.PUBLICATION) {
        options.meter.histogram(
            name = "http.client.request.duration",
            description = "End-to-end duration of the downstream pipeline per request.",
            unit = "ms",
        )
    }

    fun shouldCaptureBody(): Boolean = options.logLevel == HttpLogLevel.BODY_AND_HEADERS

    /**
     * Wraps [request]'s body in a bounded [LoggableRequestBody] when body capture is enabled,
     * returning the (possibly rewritten) outgoing request and the wrapper (or `null` when capture
     * is disabled or the request has no body). The tap is capped at `bodyPreviewMaxBytes` so a
     * multi-GB upload mirrors only a bounded preview into memory while the full payload still
     * streams to the transport.
     */
    fun wrapRequestBody(request: Request): Pair<Request, LoggableRequestBody?> {
        val requestBody = request.body
        val wrapped =
            if (shouldCaptureBody() && requestBody != null) {
                LoggableRequestBody.bounded(requestBody, Io.provider, options.bodyPreviewMaxBytes.toLong())
            } else {
                null
            }
        val outgoing =
            if (wrapped != null) {
                request.newBuilder().body(wrapped).build()
            } else {
                request
            }
        return outgoing to wrapped
    }

    /**
     * Wraps [response]'s body in a bounded [LoggableResponseBody] and forces the bounded drain so a
     * preview is available to log. An unknown-length (`contentLength() < 0`) body is left unwrapped
     * — draining it could block the draining thread on a slow producer (see the class KDoc), so
     * such a body streams to the caller untouched with no preview. Known-length bodies keep the
     * bounded preview.
     */
    fun wrapResponseForLogging(response: Response): Response {
        val responseBody = response.body
        if (!shouldCaptureBody() || responseBody == null) return response
        // Skip capture for an unknown-length (streaming) body: the bounded drain runs on the
        // draining thread (caller thread in sync, completion thread in async) and would block on a
        // slow / idle producer, stalling time-to-first-byte. The body streams to the caller
        // unwrapped with no preview; the event still carries response.content.length = -1.
        if (responseBody.contentLength() < 0L) return response
        // Bound the in-memory capture to the preview size. The full body still streams to the
        // caller via the wrapper's live tail; only a preview prefix is buffered.
        val wrapped = LoggableResponseBody.bounded(responseBody, Io.provider, options.bodyPreviewMaxBytes.toLong())
        // Force drain so we have bytes to log. Done here (not in the event emit) so a logging
        // failure can't mask the drain error from the caller — they still get the wrapped body
        // with the cached exception surfaced on source().
        try {
            wrapped.snapshot(options.bodyPreviewMaxBytes)
        } catch (t: Throwable) {
            // Drain itself records the exception on the wrapper; capture for emit, but don't
            // let it propagate — the caller will see it on next source() call.
            logger.atWarning()
                .event("http.instrumentation.response_drain_failed")
                .field("error.type", t.javaClass.simpleName ?: "Throwable")
                .cause(t)
                .log()
        }
        return response.newBuilder().body(wrapped).build()
    }

    fun emitRequestEvent(
        request: Request,
        redactedUrl: String,
    ) {
        if (options.logLevel == HttpLogLevel.NONE) return
        try {
            val ev =
                logger.atInfo()
                    .event("http.request")
                    .field("http.request.method", request.method.name)
                    .field("url.full", redactedUrl)
                    .field("request.content.length", request.body?.contentLength() ?: -1L)
            appendHeadersFields(ev, request.headers, prefix = "http.request.header.")
            ev.log()
        } catch (t: Throwable) {
            emitInstrumentationError("http.instrumentation.emit_request_failed", "request_event", t)
        }
    }

    fun emitResponseEvent(
        request: Request,
        response: Response,
        redactedUrl: String,
        elapsedMs: Double,
        requestBody: LoggableRequestBody?,
    ) {
        if (options.logLevel == HttpLogLevel.NONE) return
        try {
            val ev =
                logger.atInfo()
                    .event("http.response")
                    .field("http.request.method", request.method.name)
                    .field("url.full", redactedUrl)
                    .field("http.response.status_code", response.status.code.toLong())
                    .field("http.response.duration_ms", elapsedMs)
                    .field("response.content.length", response.body?.contentLength() ?: -1L)
            appendHeadersFields(ev, response.headers, prefix = "http.response.header.")
            if (shouldCaptureBody()) {
                if (requestBody != null) {
                    appendRequestBodyFields(ev, requestBody)
                }
                val responseBody = response.body
                if (responseBody is LoggableResponseBody) {
                    appendResponseBodyFields(ev, responseBody)
                }
            }
            ev.log()
        } catch (t: Throwable) {
            emitInstrumentationError("http.instrumentation.emit_response_failed", "response_event", t)
        }
    }

    fun emitFailureEvent(
        request: Request,
        redactedUrl: String,
        cause: Throwable,
        elapsedMs: Double,
        requestBody: LoggableRequestBody?,
    ) {
        if (options.logLevel == HttpLogLevel.NONE) return
        try {
            val ev =
                logger.atWarning()
                    .event("http.response")
                    .field("http.request.method", request.method.name)
                    .field("url.full", redactedUrl)
                    .field("error.type", cause.javaClass.simpleName ?: "Throwable")
                    .field("http.response.duration_ms", elapsedMs)
                    .cause(cause)
            if (shouldCaptureBody() && requestBody != null) {
                appendRequestBodyFields(ev, requestBody)
            }
            ev.log()
        } catch (t: Throwable) {
            emitInstrumentationError("http.instrumentation.emit_failure_failed", "failure_event", t)
        }
    }

    /**
     * Appends the request-body size / preview fields. `request.body.size` is the captured-preview
     * size; `request.body.actual_size` carries the body's declared length when known (`>= 0`); and
     * `request.body.preview_truncated` flags a preview that is only a prefix of a larger body.
     */
    private fun appendRequestBodyFields(
        ev: LoggingEvent,
        requestBody: LoggableRequestBody,
    ) {
        val preview = requestBody.snapshot(options.bodyPreviewMaxBytes)
        ev.field("request.body.size", preview.size.toLong())
            .field("request.body.preview", BodyPreview.render(preview, requestBody.mediaType()))
        val actualSize = requestBody.contentLength()
        if (actualSize >= 0L) {
            ev.field("request.body.actual_size", actualSize)
        }
        ev.field("request.body.preview_truncated", isPreviewTruncated(actualSize, preview.size))
    }

    /**
     * Appends the response-body size / preview fields. `response.body.size` is the captured-preview
     * size; `response.body.actual_size` carries the full body length when it is known; and
     * `response.body.preview_truncated` flags a preview that is only a prefix of a larger body. The
     * truncation state comes from [LoggableResponseBody.isFullyCaptured], so it is derived from the
     * existing capture without re-reading the body.
     */
    private fun appendResponseBodyFields(
        ev: LoggingEvent,
        responseBody: LoggableResponseBody,
    ) {
        val preview = responseBody.snapshot(options.bodyPreviewMaxBytes)
        ev.field("response.body.size", preview.size.toLong())
            .field("response.body.preview", BodyPreview.render(preview, responseBody.mediaType()))
        val actualSize = responseBody.contentLength()
        if (actualSize >= 0L) {
            ev.field("response.body.actual_size", actualSize)
        }
        // The wrapper fully captured the body only when it fit within the cap; otherwise the
        // preview is a prefix of a larger body and is therefore truncated.
        ev.field("response.body.preview_truncated", !responseBody.isFullyCaptured)
        responseBody.captureException?.let {
            ev.field("response.body.drain_error", it.javaClass.simpleName ?: "Throwable")
        }
    }

    /**
     * Decides whether a captured request-body preview is a truncated prefix. When the body's
     * declared length is known, the preview is truncated iff that length exceeds the preview cap.
     * When the length is unknown (`< 0`), fall back to whether the preview filled the cap — a
     * shorter preview means the whole body was captured.
     */
    private fun isPreviewTruncated(
        actualSize: Long,
        previewSize: Int,
    ): Boolean =
        if (actualSize >= 0L) {
            actualSize > options.bodyPreviewMaxBytes.toLong()
        } else {
            previewSize >= options.bodyPreviewMaxBytes
        }

    private fun appendHeadersFields(
        ev: LoggingEvent,
        headers: Headers,
        prefix: String,
    ) {
        // Iterate the headers actually present rather than the allow-list — the allow-list is
        // usually larger than the headers on any one request.
        for ((nameLower, values) in headers.entries()) {
            val typed = HttpHeaderName.fromString(nameLower)
            when {
                options.allowedHeaderNames.contains(typed) ->
                    ev.field(prefix + nameLower, joinHeaderValues(values))
                options.isRedactedHeaderNamesLoggingEnabled ->
                    ev.field(prefix + nameLower, "REDACTED")
                // else: silently omit
            }
        }
    }

    private fun joinHeaderValues(values: List<String>): String =
        if (values.size == 1) values[0] else values.joinToString(", ")

    fun safeRedact(request: Request): String =
        try {
            UrlRedactor.redact(request.url, options.allowedQueryParamNames)
        } catch (t: Throwable) {
            "[malformed url]"
        }

    fun spanAttributes(
        request: Request,
        redactedUrl: String,
    ): Map<String, Any> =
        mapOf(
            "http.request.method" to request.method.name,
            "url.full" to redactedUrl,
        )

    fun recordMetrics(
        request: Request,
        statusCode: Int,
        elapsedMs: Double,
        errorType: String?,
    ) {
        val attrs: Map<String, Any> =
            if (errorType != null) {
                mapOf(
                    "http.request.method" to request.method.name,
                    "error.type" to errorType,
                )
            } else {
                mapOf(
                    "http.request.method" to request.method.name,
                    "http.response.status_code" to statusCode,
                )
            }
        requestCounter.add(1L, attrs)
        latencyHistogram.record(elapsedMs, attrs)
    }

    fun elapsedMillis(startNanos: Long): Double = (clock.monotonic() - startNanos) / NANOS_PER_MILLI_DOUBLE

    private fun emitInstrumentationError(
        event: String,
        phase: String,
        t: Throwable,
    ) {
        // Best-effort secondary log. If this also throws, swallow — we're inside the logging
        // path already, and a thrown exception would corrupt the outer caller's flow.
        try {
            logger.atWarning()
                .event(event)
                .field("error.phase", phase)
                .field("error.type", t.javaClass.simpleName ?: "Throwable")
                .cause(t)
                .log()
        } catch (_: Throwable) {
            // Intentionally swallowed — logging is best-effort.
        }
    }

    private companion object {
        // Nanoseconds in one millisecond, expressed as Double so the division returns
        // millisecond fractions (e.g. 1.234 ms) for high-resolution latency histograms.
        private const val NANOS_PER_MILLI_DOUBLE = 1_000_000.0
    }
}
