/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("SpanExtensions")

package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC

/**
 * Activates [this] span via [Span.makeCurrent] and additionally pushes `trace.id` and
 * `span.id` onto SLF4J MDC for the lifetime of the returned scope. Closing the scope
 * restores any previous MDC values for those keys (or removes them if previously unset),
 * then closes the underlying scope.
 *
 * For [non-recording spans][Span.isRecording] the MDC push is skipped — non-recording
 * spans have no trace/span identifiers worth attaching — and the call delegates straight
 * to [Span.makeCurrent].
 *
 * ## Propagation caveat
 *
 * MDC is per-thread. The keys pushed here are visible to log events emitted on **this**
 * thread between activation and close, but they do NOT automatically propagate across
 * [java.util.concurrent.CompletableFuture] continuations or coroutine suspensions. For
 * coroutine callers, use `kotlinx-coroutines-slf4j`'s `MDCContext` element. For raw
 * future chains, capture and restore manually.
 */
public fun Span.makeCurrentWithLoggingContext(): TracingScope {
    // Check isRecording BEFORE calling makeCurrent so that a non-recording span whose
    // makeCurrent() is not a no-op (e.g. a real OTel non-sampled span that still pushes
    // propagation context) still has its scope properly returned without the MDC wrapper.
    // The caller sees the raw scope and is responsible for closing it.
    if (!isRecording) return makeCurrent()
    val inner = makeCurrent()
    val ctx = context
    val traceId = ctx.traceId.value
    val spanId = ctx.spanId.value
    val prevTraceId = MDC.get(MDC_TRACE_ID)
    val prevSpanId = MDC.get(MDC_SPAN_ID)
    MDC.put(MDC_TRACE_ID, traceId)
    MDC.put(MDC_SPAN_ID, spanId)
    return TracingScope {
        try {
            if (prevTraceId == null) MDC.remove(MDC_TRACE_ID) else MDC.put(MDC_TRACE_ID, prevTraceId)
            if (prevSpanId == null) MDC.remove(MDC_SPAN_ID) else MDC.put(MDC_SPAN_ID, prevSpanId)
        } finally {
            inner.close()
        }
    }
}

/** SLF4J MDC key for the W3C trace id. Lowercase-dotted to match the SDK's field-naming convention. */
internal const val MDC_TRACE_ID: String = "trace.id"

/** SLF4J MDC key for the W3C span id. */
internal const val MDC_SPAN_ID: String = "span.id"
