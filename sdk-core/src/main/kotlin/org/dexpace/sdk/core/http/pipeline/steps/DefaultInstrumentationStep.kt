/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.LoggingEvent
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.UrlRedactor
import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.util.Clock
import java.io.IOException

/**
 * Default [InstrumentationStep]. Emits per-request `http.request` and `http.response` structured
 * events, wraps the request/response body in [LoggableRequestBody] / [LoggableResponseBody] when
 * [HttpLogLevel.BODY_AND_HEADERS] is configured, starts/ends a span via the configured
 * [org.dexpace.sdk.core.instrumentation.Tracer], and records request count + latency on the
 * configured [org.dexpace.sdk.core.instrumentation.metrics.Meter].
 *
 * ## Body capture semantics
 *
 * - **Request body**: wrapped before send; bytes are captured during the transport's `writeTo`
 *   via a `TeeSink`. The tap is capped at `bodyPreviewMaxBytes`, so a large upload mirrors only
 *   a bounded preview into memory while the full payload streams to the transport. After send
 *   returns, the captured snapshot is emitted in the `http.response` event.
 * - **Response body**: wrapped after send with the in-memory capture bounded to
 *   `bodyPreviewMaxBytes`. The wrapper drains lazily on first `source()` / `snapshot()`; this
 *   step calls `snapshot(bodyPreviewMaxBytes)` to force the bounded drain and log a preview.
 *   A body larger than the cap still streams in full to the caller (the wrapper replays the
 *   captured prefix then continues from the live tail); a body within the cap stays fully
 *   repeatable.
 *
 * ## Failure handling
 *
 * If the downstream pipeline throws, this step emits an `http.response` event with the
 * exception class / message attached, ends the span via [Span.end] (throwable variant), and
 * rethrows. Logging itself never fails the request — exceptions inside the logging path are
 * caught and re-emitted as an `http.instrumentation.error` warning.
 *
 * ## Thread-safety
 *
 * Stateless after construction (the `ClientLogger`, counters, and histograms are reused). Safe
 * to share across concurrent requests.
 *
 * ## Cancellation
 *
 * Inherits the SDK-wide convention: blocking calls in the wrapped pipeline respect
 * `Thread.interrupt()`; this step does not introduce additional blocking.
 */
public class DefaultInstrumentationStep
    @JvmOverloads
    constructor(
        private val options: HttpInstrumentationOptions = HttpInstrumentationOptions(),
        private val clock: Clock = Clock.SYSTEM,
        internal val logger: ClientLogger = ClientLogger(DefaultInstrumentationStep::class),
    ) : InstrumentationStep() {
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

        @Throws(IOException::class)
        override fun process(
            request: Request,
            next: PipelineNext,
        ): Response {
            val redactedUrl = safeRedact(request)
            val span =
                options.tracer.startSpan(
                    name = "http ${request.method.name}",
                    attributes = spanAttributes(request, redactedUrl),
                )
            val startNanos = clock.monotonic()

            val requestBody = request.body
            val wrappedRequestBody =
                if (shouldCaptureBody() && requestBody != null) {
                    // Cap the request-side tap so a multi-GB upload only mirrors a bounded preview
                    // into memory while still streaming the full payload to the transport.
                    LoggableRequestBody.bounded(requestBody, Io.provider, options.bodyPreviewMaxBytes.toLong())
                } else {
                    null
                }
            val outgoing =
                if (wrappedRequestBody != null) {
                    request.newBuilder().body(wrappedRequestBody).build()
                } else {
                    request
                }

            val response: Response =
                span.makeCurrentWithLoggingContext().use {
                    emitRequestEvent(outgoing, redactedUrl)
                    try {
                        val raw = next.process(outgoing)
                        val wrapped = wrapResponseForLogging(raw)
                        val elapsedMs = elapsedMillis(startNanos)
                        emitResponseEvent(outgoing, wrapped, redactedUrl, elapsedMs, wrappedRequestBody)
                        recordMetrics(request, statusCode = wrapped.status.code, elapsedMs, errorType = null)
                        span.end()
                        wrapped
                    } catch (t: Throwable) {
                        val elapsedMs = elapsedMillis(startNanos)
                        emitFailureEvent(outgoing, redactedUrl, t, elapsedMs, wrappedRequestBody)
                        recordMetrics(
                            request,
                            statusCode = -1,
                            elapsedMs,
                            errorType = t::class.java.simpleName ?: "Throwable",
                        )
                        span.end(t)
                        throw t
                    }
                }
            return response
        }

        private fun shouldCaptureBody(): Boolean = options.logLevel == HttpLogLevel.BODY_AND_HEADERS

        private fun wrapResponseForLogging(response: Response): Response {
            val responseBody = response.body
            if (!shouldCaptureBody() || responseBody == null) return response
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

        private fun emitRequestEvent(
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

        private fun emitResponseEvent(
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
                        val preview = requestBody.snapshot(options.bodyPreviewMaxBytes)
                        ev.field("request.body.size", preview.size.toLong())
                            .field("request.body.preview", utf8Preview(preview))
                    }
                    val responseBody = response.body
                    if (responseBody is LoggableResponseBody) {
                        val preview = responseBody.snapshot(options.bodyPreviewMaxBytes)
                        ev.field("response.body.size", preview.size.toLong())
                            .field("response.body.preview", utf8Preview(preview))
                        responseBody.captureException?.let {
                            ev.field("response.body.drain_error", it.javaClass.simpleName ?: "Throwable")
                        }
                    }
                }
                ev.log()
            } catch (t: Throwable) {
                emitInstrumentationError("http.instrumentation.emit_response_failed", "response_event", t)
            }
        }

        private fun emitFailureEvent(
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
                    val preview = requestBody.snapshot(options.bodyPreviewMaxBytes)
                    ev.field("request.body.size", preview.size.toLong())
                        .field("request.body.preview", utf8Preview(preview))
                }
                ev.log()
            } catch (t: Throwable) {
                emitInstrumentationError("http.instrumentation.emit_failure_failed", "failure_event", t)
            }
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

        private fun safeRedact(request: Request): String =
            try {
                UrlRedactor.redact(request.url, options.allowedQueryParamNames)
            } catch (t: Throwable) {
                "[malformed url]"
            }

        private fun spanAttributes(
            request: Request,
            redactedUrl: String,
        ): Map<String, Any> =
            mapOf(
                "http.request.method" to request.method.name,
                "url.full" to redactedUrl,
            )

        private fun recordMetrics(
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

        private fun elapsedMillis(startNanos: Long): Double = (clock.monotonic() - startNanos) / NANOS_PER_MILLI_DOUBLE

        private fun utf8Preview(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            // Defensive: a snapshot that ends mid-UTF-8 codepoint shouldn't crash the log line.
            // String(bytes, charset) replaces invalid sequences rather than throwing.
            return String(bytes, Charsets.UTF_8)
        }

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
