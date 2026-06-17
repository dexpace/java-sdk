/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.MdcSnapshot
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Async counterpart of [DefaultInstrumentationStep]. Emits the same `http.request` /
 * `http.response` / failure events, drives the same span and metric lifecycle, and is
 * configured with the same [HttpInstrumentationOptions]. The emit / redact / preview / metrics
 * logic is shared with the sync step via [InstrumentationEmitters]; the only structural
 * difference is that [processAsync] returns a [CompletableFuture] composed via `.handle` so the
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
 * Identical to the sync step — see its KDoc and [InstrumentationEmitters]. As there, an
 * **unknown-length** (streaming) response body (`contentLength() < 0`) is left unwrapped:
 * the response-body drain runs on the future-completion thread here, and draining a slow / idle
 * producer would stall that thread. Known-length bodies are wrapped and bounded to the preview
 * size.
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

        private val emitters = InstrumentationEmitters(options, clock, logger)

        override fun processAsync(
            request: Request,
            next: AsyncPipelineNext,
        ): CompletableFuture<Response> {
            val redactedUrl = emitters.safeRedact(request)
            val span =
                options.tracer.startSpan(
                    name = "http ${request.method.name}",
                    attributes = emitters.spanAttributes(request, redactedUrl),
                )
            val startNanos = clock.monotonic()

            val (outgoing, wrappedRequestBody) = emitters.wrapRequestBody(request)

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
                    emitters.emitRequestEvent(outgoing, redactedUrl)
                    try {
                        next.processAsync(outgoing)
                    } catch (e: Throwable) {
                        // Synchronous throw from the next step (e.g. argument validation).
                        // Normalise to a failed future so callers get the uniform async contract.
                        val elapsedMs = emitters.elapsedMillis(startNanos)
                        emitters.emitFailureEvent(outgoing, redactedUrl, e, elapsedMs, wrappedRequestBody)
                        emitters.recordMetrics(
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
            val elapsedMs = emitters.elapsedMillis(startNanos)
            if (err != null) {
                // CompletableFuture wraps with CompletionException; unwrap so events see the original.
                val cause = Futures.unwrap(err)
                mdc.withMdc {
                    emitters.emitFailureEvent(outgoing, redactedUrl, cause, elapsedMs, wrappedRequestBody)
                    emitters.recordMetrics(
                        request,
                        statusCode = -1,
                        elapsedMs,
                        errorType = cause::class.java.simpleName ?: "Throwable",
                    )
                    span.end(cause)
                }
                // Re-throw via the future graph — handle's return becomes the new value/completion.
                // The CompletionException wrap is correct per CompletableFuture.join() semantics:
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
                    val wrapped = emitters.wrapResponseForLogging(raw!!)
                    emitters.emitResponseEvent(outgoing, wrapped, redactedUrl, elapsedMs, wrappedRequestBody)
                    emitters.recordMetrics(request, statusCode = wrapped.status.code, elapsedMs, errorType = null)
                    span.end()
                    wrapped
                }
            }
        }
    }
