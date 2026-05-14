package org.dexpace.sdk.core.instrumentation

/**
 * Unit of work in a distributed trace. A span captures attributes, errors, and timing for
 * a single operation; spans correlate across services through their shared trace context
 * and may nest to model parent/child relationships.
 *
 * Most callers obtain a span from a [Tracer] and end it once the underlying operation
 * completes. Operations that don't perform tracing receive [NOOP], a shared no-allocation
 * singleton that satisfies the contract without recording anything.
 */
public interface Span {
    /**
     * `true` when this span is part of a sampled trace and is recording events,
     * attributes, and timing. A `false` value indicates a no-op or non-sampled span;
     * mutators on such a span are inert and any data passed to them is dropped.
     */
    public val isRecording: Boolean

    /** Trace-related metadata (trace id, span id, flags, state) for propagation and lookup. */
    public val context: InstrumentationContext

    /**
     * Attaches a key/value attribute to the span.
     *
     * @param key non-empty attribute name.
     * @param value attribute value of any type; rendering is decided by the backend.
     * @return `this`, for fluent chaining.
     */
    public fun setAttribute(key: String, value: Any): Span

    /**
     * Marks the span as having encountered an error, tagging it with [errorType] (typically
     * the exception class simple name).
     *
     * @return `this`, for fluent chaining.
     */
    public fun setError(errorType: String): Span

    /**
     * Makes this span the active span for the current execution context and returns a
     * [TracingScope] whose `close()` restores the previously active span. The returned
     * scope must be closed; prefer `use { … }` or try-with-resources from Java.
     */
    public fun makeCurrent(): TracingScope

    /**
     * Finishes the span successfully. Calling [end] (or [end(Throwable)][end]) more than
     * once, or on a non-recording span, is a documented no-op.
     */
    public fun end()

    /**
     * Finishes the span with an error condition; [throwable] is recorded as the cause for
     * later inspection by exporters. Multiple calls are no-ops, as for [end].
     */
    public fun end(throwable: Throwable)

    public companion object {
        /**
         * Shared no-operation [Span] singleton — `isRecording` is `false`, every mutator
         * returns `this`, and `end`/`makeCurrent` do nothing. Use when tracing is disabled
         * or no real span is available. Exposed as a `@JvmField` so Java callers reach it
         * via `Span.NOOP` rather than `Span.Companion.getNOOP()`.
         */
        @JvmField
        public val NOOP: Span = NoopSpan
    }
}
