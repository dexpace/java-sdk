package org.dexpace.sdk.core.instrumentation

/**
 * A no-operation implementation of the [InstrumentationContext] interface.
 * This implementation represents an invalid or non-existent tracing context and is used
 * as the default or fallback when no actual instrumentation context is available.
 */
object NoopInstrumentationContext : InstrumentationContext {

    /**
     * The unique identifier for a trace in a distributed tracing system.
     *
     * This value is a 32-character, hexadecimal-encoded string used to correlate
     * operations across a distributed system by linking spans within the same trace.
     * It adheres to the W3C Trace Context specification's `trace-id` field format,
     * ensuring uniformity and compatibility.
     *
     * In the default implementation, the `traceId` is initialized with 32 zero characters,
     * representing an invalid or unset state. A valid `traceId` should be a non-zero,
     * 32-character string.
     */
    override val traceId: TraceId = TraceId.NOOP

    /**
     * Represents the identifier of the current span in the trace context.
     *
     * By default, this value is set to a 16-character hexadecimal string of zeros
     * (`"0000000000000000"`), indicating the absence of a valid span ID.
     *
     * The `spanId` uniquely identifies a specific span within a trace when tracing is active.
     */
    override val spanId: SpanId = SpanId.NOOP

    /**
     * Represents the W3C trace-parent "trace-flags" field, which is used to indicate
     * sampling status or other trace-level options. This variable is set to "00",
     * signifying that the trace is unsampled and no additional tracing flags are active.
     */
    override val traceFlags: TraceFlags = TraceFlags.NOOP

    override val traceState: TraceState = TraceState.NOOP

    /**
     * Indicates whether the current `Span` is valid and usable.
     *
     * This property evaluates the validity of the `Span` instance, determining
     * whether it can participate in tracing operations. A value of `true`
     * implies the `Span` is valid and capable of being used, while a value of `false`
     * typically denotes a no-op or uninitialized state.
     */
    override val isValid: Boolean = false

    override val isRemote: Boolean = false

    /**
     * Represents a non-operational `Span` instance.
     *
     * This property returns `Span.NOOP`, which is a no-op implementation of the `Span` interface.
     * It is used as a default or placeholder implementation where tracing functionality is intentionally disabled.
     * The `Span.NOOP` does not record any tracing data or perform any operations but fulfills the interface requirements.
     */
    override val span: Span
        get() = Span.NOOP
}
