package org.dexpace.sdk.core.instrumentation

/**
 * Default [Tracer] implementation that returns the shared [Span.NOOP] singleton for every
 * [startSpan] call — zero allocation, no recording. Used by
 * [org.dexpace.sdk.core.http.pipeline.steps.HttpInstrumentationOptions] when no concrete tracer
 * is installed.
 */
public object NoopTracer : Tracer {
    override fun startSpan(name: String, attributes: Map<String, Any>): Span = Span.NOOP
}
