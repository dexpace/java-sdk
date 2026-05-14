package org.dexpace.sdk.core.instrumentation

/**
 * Factory for [Span] instances. The concrete OpenTelemetry adapter lives in a separate module
 * — `sdk-core` ships [NoopTracer] as the default and a single-method interface so an OTel-backed
 * tracer can be plugged in via [org.dexpace.sdk.core.http.pipeline.steps.HttpInstrumentationOptions].
 *
 * Thread-safety: implementations must be safe to invoke from multiple threads — pipelines call
 * [startSpan] once per request and pipelines are shared across concurrent sends.
 */
public interface Tracer {
    /**
     * Starts a new span. The returned span is `recording` if and only if the tracer has an active
     * sampler that selected this trace; consumers should call [Span.end] regardless to mark the
     * lifecycle, since `end` on a non-recording span is a documented no-op.
     *
     * Note: `@JvmOverloads` is not supported on interface methods in Kotlin, so the default
     * `attributes` parameter is Kotlin-only. Java callers should use the companion-object
     * helper [Tracer.startSpan] which provides a two-argument form without requiring
     * `Collections.emptyMap()` at every call site.
     */
    public fun startSpan(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
    ): Span

    public companion object {
        /**
         * Java convenience helper: starts a span with no attributes by forwarding to
         * [Tracer.startSpan] with an empty map.
         *
         * Java callers write `Tracer.startSpan(tracer, "name")` instead of
         * `tracer.startSpan("name", Collections.emptyMap())`.
         * Kotlin callers continue to use the default-parameter form directly:
         * `tracer.startSpan("name")`.
         */
        @JvmStatic
        public fun startSpan(
            tracer: Tracer,
            name: String,
        ): Span = tracer.startSpan(name, emptyMap())
    }
}
