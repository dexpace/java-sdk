package org.dexpace.sdk.core.instrumentation

/**
 * Represents the context for instrumentation and distributed tracing.
 *
 * An `InstrumentationContext` provides essential information about a distributed trace.
 * It is used to propagate trace-related metadata across boundaries in distributed systems,
 * enabling correlation and observability across operations.
 *
 * Compliant to the [W3C Trace Context specification](https://www.w3.org/TR/trace-context/).
 */
interface InstrumentationContext {
    /**
     * Represents the unique identifier for a trace within a distributed tracing system.
     *
     * The `traceId` is used to correlate and group spans that belong to the same trace,
     * enabling end-to-end visibility across a system’s components. It acts as a key
     * for tracing and monitoring operations across distributed services.
     */
    val traceId: TraceId
    /**
     * Represents the unique identifier for a span within a distributed trace.
     *
     * `spanId` is used to track and correlate individual units of work or operations
     * in a distributed tracing system. Each span within a trace has a unique span ID,
     * which is typically generated when the span is created. This ID is essential
     * for linking spans together to form a trace hierarchy, enabling observability
     * across distributed systems. A special `NOOP` instance of `SpanId` is available
     * to represent a no-operation or invalid state.
     */
    val spanId: SpanId
    /**
     * Represents the trace flags associated with a trace in a distributed tracing system.
     *
     * The `traceFlags` property provides information about the status or behavior of a trace,
     * such as whether it is sampled for further processing or analysis. This metadata plays an
     * important role in determining the level of observability applied to specific traces.
     *
     * Common uses include indicating sampling decisions for spans within a trace.
     */
    val traceFlags: TraceFlags

    /**
     * Represents the state of the trace, encapsulating additional distributed tracing metadata.
     *
     * The `traceState` variable can carry a collection of vendor-specific key-value pairs used for
     * fine-grained trace context propagation. This allows for interoperability and enhanced trace
     * data sharing between different instrumentation implementations or tracing systems.
     *
     * Certain special values (e.g., `TraceState.NOOP`) may represent a no-operation state
     * where no additional metadata is present.
     */
    val traceState: TraceState

    /**
     * Indicates whether the current `InstrumentationContext` instance is valid.
     *
     * A valid context is one that contains proper identifiers and state required for distributed tracing
     * operations. When `true`, the `InstrumentationContext` represents a meaningful trace context
     * suitable for trace propagation and correlation. If `false`, the context is considered invalid
     * and may represent a default, uninitialized, or noop state.
     */
    val isValid: Boolean

    /**
     * Indicates whether the trace context associated with this instance was propagated from a remote system.
     *
     * A value of `true` means that the trace context (e.g., trace ID, span ID, trace flags) was received
     * from an external source, such as another service in a distributed application. This typically implies
     * that this instance represents the continuation of a trace initiated elsewhere. Conversely, a value
     * of `false` indicates that the trace context was locally generated or is not associated with
     * an external system.
     *
     * This property is commonly used to differentiate between traces that originate within the current system
     * and those that are propagated through inter-system communication.
     */
    val isRemote: Boolean

    /**
     * Represents the current active `Span` within the `InstrumentationContext`.
     *
     * The `span` serves as the operational context for distributed tracing, where it tracks
     * metadata and lifecycle of the ongoing trace or operation. This enables the correlation
     * of events across systems and provides observability into the workflow.
     *
     * In cases where the tracing system is disabled or the `InstrumentationContext` is invalid,
     * this may resolve to a no-operation (`NOOP`) `Span` implementation that performs no actions.
     */
    val span: Span
}

@JvmInline
value class TraceId(val traceId: String) {
    companion object {
        /**
         * Represents a no-operation (NOOP) `TraceId` constant used when tracing is disabled or inactive.
         *
         * This constant defines a default trace ID value that signifies the absence of meaningful tracing context.
         * It is commonly used in scenarios where tracing is not enabled, and a placeholder implementation is required.
         */
        val NOOP = TraceId("00000000000000000000000000000000")
    }
}

@JvmInline
value class SpanId(val spandId: String) {
    companion object {
        /**
         * Represents a no-operation (NOOP) span identifier.
         *
         * This constant is used to represent a disabled or inactive span with no associated tracing context.
         * It serves as the default or fallback span identifier when tracing functionality is not enabled
         * or when a valid span ID is not available.
         */
        val NOOP = SpanId("0000000000000000")
    }
}

@JvmInline
value class TraceFlags(val traceFlags: String) {
    companion object {
        /**
         * A no-operation trace flags implementation representing a disabled or inactive state.
         *
         * The `NOOP` constant is used as a placeholder for trace flags that do not participate in active
         * tracing. It ensures no actions or side effects occur during trace context propagation or instrumentation.
         */
        val NOOP = TraceFlags("00")
    }
}

@JvmInline
value class TraceState(val traceState: String) {
    companion object {
        /**
         * Represents a no-operation (NOOP) `TraceState` instance.
         *
         * This constant `TraceState` instance is intended to signify a default, inactive, or empty trace state.
         * It provides a consistent representation for cases where no meaningful trace state is required or available.
         */
        val NOOP = TraceState("")
    }
}
