package org.dexpace.sdk.core.instrumentation

/**
 * No-operation [InstrumentationContext] used as the default or fallback when tracing is
 * disabled. Every identifier resolves to its `NOOP` sentinel: the trace id is the W3C
 * reserved all-zero value, the span id is sixteen zero hex chars, trace flags are `"00"`,
 * and the trace state is empty.
 *
 * Shipped as a Kotlin `object` so the instance is shared process-wide with no allocation.
 */
object NoopInstrumentationContext : InstrumentationContext {
    override val traceIdType: TraceIdType = TraceIdType.NOOP

    /**
     * Reserved invalid trace id — 32 hex zeros, per the W3C Trace Context spec. A valid
     * trace id is non-zero; the all-zero sentinel signals "no trace" to downstream observers.
     */
    override val traceId: TraceId = TraceId.NOOP

    /** Reserved invalid span id — 16 hex zeros, indicating the absence of a real span. */
    override val spanId: SpanId = SpanId.NOOP

    /** W3C trace-flags `"00"` — unsampled, no flags set. */
    override val traceFlags: TraceFlags = TraceFlags.NOOP

    /** Empty trace state — no vendor-specific entries. */
    override val traceState: TraceState = TraceState.NOOP

    /** Always `false` — the no-op context carries no real trace identifiers. */
    override val isValid: Boolean = false

    /** Always `false` — the no-op context is not propagated from a remote system. */
    override val isRemote: Boolean = false

    /** Always [Span.NOOP] — the no-op context cannot host a recording span. */
    override val span: Span = Span.NOOP
}
