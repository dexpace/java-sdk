/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.LoggingEvent
import org.dexpace.sdk.core.instrumentation.MdcSnapshot
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.UrlRedactor
import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Async counterpart of [DefaultInstrumentationStep]. Emits the same `http.request` /
 * `http.response` / failure events, drives the same span and metric lifecycle, and is
 * configured with the same [HttpInstrumentationOptions]. The only structural difference
 * is that [processAsync] returns a [CompletableFuture] composed via `.handle` so the
 * response event fires on the completion thread.
 *
 * ## Span-thread caveat
 *
 * The future's completion handler runs on whatever thread completes the underlying
 * future. The MDC push from [Span.makeCurrentWithLoggingContext] is per-thread; if the
 * completion thread differs from the calling thread, MDC won't carry across the boundary.
 * The synchronous portion (everything up to and including the `next.processAsync` call)
 * runs inside the MDC-aware scope; the `.handle` callback runs after the scope has
 * closed. Downstream steps that emit events from within their own synchronous portion
 * benefit from MDC; events fired from CompletableFuture continuations do not.
 *
 * ## Body capture / failure semantics
 *
 * Identical to the sync step — see its KDoc.
 *
 * ## Thread-safety
 *
 * Stateless after construction (the `ClientLogger`, counters, and histograms are reused). Safe
 * to share across concurrent requests.
 */
public class DefaultAsyncInstrumentationStep
    @JvmOverloads
    constructor(
        private val options: HttpInstrumentationOptions = HttpInstrumentationOptions(),
        private val clock: Clock = Clock.SYSTEM,
        internal val logger: ClientLogger = ClientLogger(DefaultAsyncInstrumentationStep::class),
    ) : AsyncHttpStep {
        override val stage: Stage = Stage.LOGGING

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

        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> {
            val redactedUrl = safeRedact(request)
            val span =
                options.tracer.startSpan(
                    name = "http ${request.method.name}",
                    attributes = spanAttributes(request, redactedUrl),
                )
            val startNanos = clock.monotonic()

            val (outgoing, wrappedRequestBody) = buildOutgoingRequest(request)

            // Synchronous portion is wrapped in the MDC-aware scope. The scope closes BEFORE
            // the future's continuation runs — so the response/failure events emitted in
            // .handle do NOT get MDC by default. We capture the MDC snapshot INSIDE the
            // use{} block (after makeCurrentWithLoggingContext has pushed trace.id / span.id)
            // and restore it in each branch of the .handle callback.
            var mdc: MdcSnapshot = MdcSnapshot.capture() // will be overwritten inside the scope
            val downstream: CompletableFuture<Response> =
                span.makeCurrentWithLoggingContext().use {
                    // Capture after the scope has pushed trace.id / span.id so the snapshot carries them.
                    mdc = MdcSnapshot.capture()
                    emitRequestEvent(outgoing, redactedUrl)
                    try {
                        next.processAsync(outgoing)
                    } catch (e: Throwable) {
                        // Synchronous throw from the next step (e.g. argument validation).
                        // Normalise to a failed future so callers get the uniform async contract.
                        val elapsedMs = elapsedMillis(startNanos)
                        emitFailureEvent(outgoing, redactedUrl, e, elapsedMs, wrappedRequestBody)
                        recordMetrics(
                            request,
                            statusCode = -1,
                            elapsedMs,
                            errorType = e::class.java.simpleName ?: "Throwable",
                        )
                        span.end(e)
                        return Futures.failed(e)
                    }
                }

            val capturedMdc = mdc
            return downstream.handle { raw, err ->
                handleCompletion(
                    span = span,
                    mdc = capturedMdc,
                    raw = raw,
                    err = err,
                    startNanos = startNanos,
                    request = request,
                    outgoing = outgoing,
                    redactedUrl = redactedUrl,
                    wrappedRequestBody = wrappedRequestBody,
                )
            }
        }

        /**
         * Wraps the request body in a [LoggableRequestBody] when body capture is enabled.
         * Returns the (possibly rewritten) outgoing request and the wrapper (or null when
         * capture is disabled or the original request has no body).
         */
        private fun buildOutgoingRequest(request: Request): Pair<Request, LoggableRequestBody?> {
            val requestBody = request.body
            val wrappedRequestBody =
                if (shouldCaptureBody() && requestBody != null) {
                    LoggableRequestBody(requestBody)
                } else {
                    null
                }
            val outgoing =
                if (wrappedRequestBody != null) {
                    request.newBuilder().body(wrappedRequestBody).build()
                } else {
                    request
                }
            return outgoing to wrappedRequestBody
        }

        /**
         * Handles completion of the downstream future (either success or failure).
         * Emits the appropriate instrumentation events, records metrics, ends the span,
         * and returns the (possibly logging-wrapped) response on success or re-throws on failure.
         *
         * This is the body of the `.handle { raw, err -> … }` callback, extracted to keep
         * [processAsync] within the line-count budget. The wide parameter list captures the
         * full call-site closure as explicit arguments rather than a stack of `private val`
         * fields — keeps the function pure / testable. Refactoring to a data class would
         * not measurably improve clarity.
         */
        @Suppress("LongParameterList")
        private fun handleCompletion(
            span: Span,
            mdc: MdcSnapshot,
            raw: Response?,
            err: Throwable?,
            startNanos: Long,
            request: Request,
            outgoing: Request,
            redactedUrl: String,
            wrappedRequestBody: LoggableRequestBody?,
        ): Response {
            val elapsedMs = elapsedMillis(startNanos)
            if (err != null) {
                // CompletableFuture wraps with CompletionException; unwrap so events see the original.
                val cause = Futures.unwrap(err)
                mdc.withMdc {
                    emitFailureEvent(outgoing, redactedUrl, cause, elapsedMs, wrappedRequestBody)
                    recordMetrics(
                        request,
                        statusCode = -1,
                        elapsedMs,
                        errorType = cause::class.java.simpleName ?: "Throwable",
                    )
                    span.end(cause)
                }
                // Re-throw via the future graph — handle's return becomes the new value/completion.
                // B4: The CompletionException wrap is correct per CompletableFuture.join() semantics:
                // join() rethrows a stored CompletionException as-is and the unwrap chain via
                // Futures.unwrap exposes the original cause. RuntimeException / Error are rethrown
                // directly since join() would not re-wrap them. Callers should use Futures.unwrap()
                // or asBlocking() for the original-cause guarantee.
                throw when {
                    cause is RuntimeException -> cause
                    cause is Error -> cause
                    else -> CompletionException(cause)
                }
            } else {
                return mdc.withMdc {
                    val wrapped = wrapResponseForLogging(raw!!)
                    emitResponseEvent(outgoing, wrapped, redactedUrl, elapsedMs, wrappedRequestBody)
                    recordMetrics(request, statusCode = wrapped.status.code, elapsedMs, errorType = null)
                    span.end()
                    wrapped
                }
            }
        }

        // ===== Helpers duplicated from DefaultInstrumentationStep =====
        // Duplication is intentional for this commit.
        // TODO(omar 2026-08-01): extract InstrumentationEmitters shared helper used by both DefaultInstrumentationStep and DefaultAsyncInstrumentationStep

        private fun shouldCaptureBody(): Boolean = options.logLevel == HttpLogLevel.BODY_AND_HEADERS

        private fun wrapResponseForLogging(response: Response): Response {
            val responseBody = response.body
            if (!shouldCaptureBody() || responseBody == null) return response
            val wrapped = LoggableResponseBody(responseBody)
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
