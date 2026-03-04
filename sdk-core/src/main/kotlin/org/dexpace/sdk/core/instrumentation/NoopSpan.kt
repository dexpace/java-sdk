package org.dexpace.sdk.core.instrumentation

/**
 * A no-operation implementation of the `Span` interface.
 *
 * `NoopSpan` is a placeholder implementation that performs no actions and records no tracing data.
 * This is useful when tracing is disabled or not required, providing a lightweight alternative
 * that adheres to the `Span` contract without incurring any performance overhead.
 */
object NoopSpan : Span {
    /**
     * Provides an override for the `context` property, returning a no-operation implementation
     * of the `InstrumentationContext`.
     *
     * This override is typically used in scenarios where tracing is intentionally disabled or
     * unnecessary, serving as a lightweight default implementation of `InstrumentationContext`.
     */
    override val context: InstrumentationContext
        get() = NoopInstrumentationContext

    /**
     * Indicates whether the span instance is actively recording tracing information or operating as a no-operation (NOOP) span.
     *
     * When `true`, the span is capturing and storing tracing data, such as attributes, events, or context
     * propagation information. This is typically the case for spans that are part of an active sampled trace.
     * If `false`, the span does not record any data and acts as a placeholder for non-sampled or disabled traces.
     */
    override val isRecording: Boolean = false

    /**
     * Sets a key-value attribute on the current span.
     *
     * This method is used to associate metadata with the span, such as contextual information
     * or operational details. Attributes improve traceability and observability, enabling
     * debugging and monitoring workflows.
     *
     * @param key The name of the attribute to set. Must be a non-empty string.
     * @param value The value of the attribute to associate with the given key. Can be of any type.
     * @return The current span instance, enabling method chaining.
     */
    override fun setAttribute(key: String, value: Any): Span = this

    /**
     * Sets an error condition on the current span using the specified error type.
     *
     * This method marks the span as having encountered an error and annotates it
     * with additional information about the nature of the error. Typically, this
     * is used in distributed tracing systems to capture context about issues that
     * occurred during the span's execution.
     *
     * @param errorType The type or description of the error. This should be a non-empty
     *                  string providing meaningful details about the error (e.g., "NullPointerException").
     * @return The current span instance, allowing for method chaining.
     */
    override fun setError(errorType: String): Span = this

    /**
     * Ends the span and associates an error with it using the provided throwable.
     *
     * This method concludes the lifecycle of the span, ensuring it is finalized and no further
     * modifications can be made. The provided throwable is recorded as the error condition
     * that occurred during the span's execution for traceability and diagnostics.
     *
     * @param throwable The throwable representing the error that occurred during the span's execution.
     */
    override fun end(throwable: Throwable) = Unit

    /**
     * Marks the end of the current span's lifecycle.
     *
     * This method indicates that the span is complete, and no further operations
     * (e.g., setting attributes or recording errors) can be performed on it.
     * Once this method is invoked, the span's data is finalized and may be exported
     * or processed by the tracing system.
     *
     * If the span has already been ended or is a no-operation (NOOP) span, calling
     * this method will have no effect.
     */
    override fun end() = Unit

    /**
     * Activates the current execution context for tracing operations and returns a controlled scope.
     *
     * This method makes the associated trace or span the active context for the duration
     * of the created `TracingScope`. The `TracingScope` encapsulates the lifecycle of the active context,
     * ensuring proper activation and cleanup when the scope ends. It provides a mechanism to manage
     * the span's context safely within a constrained execution block, simplifying distributed tracing workflows.
     *
     * @return A `TracingScope` instance that represents the active context for the current trace or span.
     *         The returned scope must be closed to release resources and deactivate the context properly.
     */
    override fun makeCurrent(): TracingScope = TracingScope { }
}
