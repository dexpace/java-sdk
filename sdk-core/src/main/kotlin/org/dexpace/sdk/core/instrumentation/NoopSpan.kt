package org.dexpace.sdk.core.instrumentation

/**
 * No-operation [Span] implementation that records nothing and allocates nothing.
 *
 * Returned from [NoopTracer.startSpan] and exposed as [Span.NOOP]. Every mutating method
 * returns `this`, both `end` overloads are no-ops, and [makeCurrent] reuses a cached
 * [TracingScope] singleton so the disabled-tracing path is allocation-free.
 */
object NoopSpan : Span {
    /** Reused across every [makeCurrent] call so the no-op path allocates no SAM adapter. */
    private val NOOP_SCOPE: TracingScope = TracingScope { }

    override val context: InstrumentationContext
        get() = NoopInstrumentationContext

    /** Always `false` — no data is captured. */
    override val isRecording: Boolean = false

    /** No-op; returns `this` for fluent chaining. */
    override fun setAttribute(key: String, value: Any): Span = this

    /** No-op; returns `this` for fluent chaining. */
    override fun setError(errorType: String): Span = this

    /** No-op. */
    override fun end() = Unit

    /** No-op; the throwable is silently dropped. */
    override fun end(throwable: Throwable) = Unit

    /** Returns the cached no-op [TracingScope] singleton; no allocation per call. */
    override fun makeCurrent(): TracingScope = NOOP_SCOPE
}
