package org.dexpace.sdk.core.instrumentation

/**
 * Represents a unit of work or operation in a distributed tracing system.
 *
 * Spans are used to record and track the execution of operations, capturing metadata
 * such as attributes, errors, and timestamps. They provide context for distributed traces,
 * enabling observability and correlation of events across systems. Each span has a unique
 * context and can be nested or related to other spans.
 */
interface Span {
    /**
     * Indicates whether the current `Span` instance is actively recording tracing information.
     *
     * When `true`, the `Span` is in a state where it captures events, attributes,
     * context propagation, and other tracing data. This typically applies to spans that are part of an
     * active trace with sampling enabled. Conversely, `false` indicates that the `Span`
     * is not recording and behaves as a no-operation (NOOP) implementation, often used for disabled
     * or non-sampled traces.
     */
    val isRecording: Boolean
    /**
     * Provides access to the contextual information associated with the current span.
     *
     * The `context` property offers trace-related metadata such as the trace ID, span ID,
     * trace flags, state, and validity status. It is used to propagate and manage trace context
     * across boundaries in distributed systems.
     *
     * This context represents the low-level details of a span's trace and is integral to distributed
     * tracing workflows for observability and monitoring.
     */
    val context: InstrumentationContext

    /**
     * Sets a key-value attribute on the span.
     *
     * This method is used to associate additional metadata with the span,
     * such as contextual or descriptive information. Attributes can enable
     * better observability or debugging by providing insights into span behavior.
     *
     * @param key The name of the attribute to set. Must be a non-empty string.
     * @param value The value of the attribute to set. Can be of any type.
     * @return The current span instance, allowing for method chaining.
     */
    fun setAttribute(key: String, value: Any): Span

    /**
     * Sets an error condition on the span with the specified error type.
     *
     * This method can be used to mark the span as containing an error and annotate it
     * with details about the error type. This is typically used in distributed tracing
     * systems to record error information associated with specific operations.
     *
     * @param errorType The type or description of the error. This should be a non-empty string
     *                  that provides meaningful context about the error (e.g., "NullPointerException").
     * @return The current span instance after applying the error condition.
     */
    fun setError(errorType: String): Span

    /**
     * Activates the current span within the execution context, making it the active span for tracing operations.
     *
     * This method returns a `TracingScope` that represents the controlled lifecycle of the activated span's context.
     * The returned `TracingScope` should be closed when the span is no longer needed to clean up the context properly.
     *
     * @return A `TracingScope` instance representing the active context for the current span.
     */
    fun makeCurrent(): TracingScope

    /**
     * Marks the end of the current span.
     *
     * This method is used to signal the completion of the span's lifecycle. Once invoked,
     * the span is considered finished, and no further operations (such as setting attributes
     * or recording errors) can be performed on the span.
     *
     * If the span is not actively recording or is a no-operation (NOOP) span, this method
     * will have no effect.
     */
    fun end()

    /**
     * Ends the span and records the occurrence of an error using the provided throwable.
     *
     * This method marks the end of the span's lifecycle, ensuring that the span is finalized
     * and any associated resources are released. If an error occurred during the span's execution,
     * the provided throwable will be associated with the span for debugging and traceability.
     * This method is typically used in cases where an exception has affected the operation
     * being traced.
     *
     * @param throwable The throwable that represents the error that occurred during the span's execution.
     */
    fun end(throwable: Throwable)

    /**
     * Companion object for the `Span` interface, providing static utility members or defaults
     * relevant to the `Span` interface's operation.
     *
     * The companion includes a no-operation (NOOP) implementation of the `Span` interface,
     * which is used as a default or fallback when tracing functionality is disabled or
     * unavailable. The `NOOP` instance adheres to the `Span` contract but does not perform
     * any actions or record any data, ensuring performance overhead is minimized when tracing
     * is not active.
     */
    companion object {
        /**
         * A no-operation `Span` implementation that performs no actions and represents an inactive or disabled span.
         *
         * This constant is used as a default or placeholder implementation for the `Span` interface when no actual tracing is required.
         * The `NOOP` span ignores all operations and does not record any tracing data.
         *
         * Key characteristics of `NOOP`:
         * - `isRecording` is always `false`, indicating that no span data will be recorded.
         * - Associated methods like `setAttribute` and `setError` have no effect or side-effects.
         * - The `context` is always set to `NoopInstrumentationContext`, which represents an invalid/no-op context.
         * - Methods like `end` and `makeCurrent` do nothing.
         *
         * Use `NOOP` for cases where tracing functionality is intentionally disabled or when a valid `Span` object is not available.
         */
        val NOOP = NoopSpan
    }
}
