package org.dexpace.sdk.core.instrumentation

/**
 * Factory for [Span] instances. The concrete OpenTelemetry adapter lives in a separate module
 * — `sdk-core` ships [NoopTracer] as the default and a single-method interface so an OTel-backed
 * tracer can be plugged in via [org.dexpace.sdk.core.http.pipeline.steps.HttpInstrumentationOptions].
 *
 * Thread-safety: implementations must be safe to invoke from multiple threads — pipelines call
 * [startSpan] once per request and pipelines are shared across concurrent sends.
 */
interface Tracer {
    /**
     * Starts a new span. The returned span is `recording` if and only if the tracer has an active
     * sampler that selected this trace; consumers should call [Span.end] regardless to mark the
     * lifecycle, since `end` on a non-recording span is a documented no-op.
     */
    fun startSpan(name: String, attributes: Map<String, Any> = emptyMap()): Span
}
