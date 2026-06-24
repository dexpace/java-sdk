/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
import org.dexpace.sdk.core.util.Clock
import java.io.IOException

/**
 * Default [InstrumentationStep]. Emits per-request `http.request` and `http.response` structured
 * events, wraps the request/response body in [LoggableRequestBody] / [LoggableResponseBody] when
 * [HttpLogLevel.BODY_AND_HEADERS] is configured, starts/ends a span via the configured
 * [org.dexpace.sdk.core.instrumentation.Tracer], and records request count + latency on the
 * configured [org.dexpace.sdk.core.instrumentation.metrics.Meter].
 *
 * The emit / redact / preview / metrics logic is shared with [DefaultAsyncInstrumentationStep] via
 * [InstrumentationEmitters]; this step owns only the synchronous control flow.
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
 *   repeatable. An **unknown-length** (streaming) body (`contentLength() < 0`) is left unwrapped
 *   — draining it could block the caller's thread on a slow / idle producer (SSE, long-poll,
 *   chunked trickle), so it streams to the caller with no body preview.
 *
 * ## Failure handling
 *
 * If the downstream pipeline throws, this step emits an `http.response` event with the
 * exception class / message attached, ends the span via its throwable-variant `end`, and
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
        private val emitters = InstrumentationEmitters(options, clock, logger)

        @Throws(IOException::class)
        override fun process(
            request: Request,
            next: PipelineNext,
        ): Response {
            val redactedUrl = emitters.safeRedact(request)
            val span =
                options.tracer.startSpan(
                    name = "http ${request.method.name}",
                    attributes = emitters.spanAttributes(request, redactedUrl),
                )
            val startNanos = clock.monotonic()

            val (outgoing, wrappedRequestBody) = emitters.wrapRequestBody(request)

            val response: Response =
                span.makeCurrentWithLoggingContext().use {
                    emitters.emitRequestEvent(outgoing, redactedUrl)
                    try {
                        val raw = next.process(outgoing)
                        val wrapped = emitters.wrapResponseForLogging(raw)
                        val elapsedMs = emitters.elapsedMillis(startNanos)
                        emitters.emitResponseEvent(outgoing, wrapped, redactedUrl, elapsedMs, wrappedRequestBody)
                        emitters.recordMetrics(request, statusCode = wrapped.status.code, elapsedMs, errorType = null)
                        span.end()
                        wrapped
                    } catch (t: Throwable) {
                        val elapsedMs = emitters.elapsedMillis(startNanos)
                        emitters.emitFailureEvent(outgoing, redactedUrl, t, elapsedMs, wrappedRequestBody)
                        emitters.recordMetrics(
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
    }
