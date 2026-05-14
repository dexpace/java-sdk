package org.dexpace.sdk.core.instrumentation

/**
 * Metadata carried with every traced operation: the trace and span identifiers plus the
 * W3C trace flags and state. Implementations propagate this context across service
 * boundaries so spans on either side correlate into one logical trace.
 *
 * Compliant with the [W3C Trace Context specification](https://www.w3.org/TR/trace-context/).
 */
public interface InstrumentationContext {
    /**
     * Encoding flavour of [traceId]. Drives how the trace id is generated and rendered for
     * propagation — different backends (Datadog vs. W3C) expect different wire formats.
     */
    public val traceIdType: TraceIdType

    /** Identifier shared by every span in the same logical trace; see [TraceIdType] for format. */
    public val traceId: TraceId

    /** Identifier of the current span within its parent trace; 16 hex chars per W3C. */
    public val spanId: SpanId

    /** W3C trace-flags byte, e.g. the sampled bit. */
    public val traceFlags: TraceFlags

    /**
     * W3C trace-state — vendor-specific `key=value` list propagated alongside the trace
     * context. May be [TraceState.NOOP] when no extra metadata is present.
     */
    public val traceState: TraceState

    /** `true` when the context contains real (non-sentinel) identifiers suitable for propagation. */
    public val isValid: Boolean

    /** `true` when this context was extracted from an inbound request rather than minted locally. */
    public val isRemote: Boolean

    /** Currently active span associated with this context; [Span.NOOP] when tracing is disabled. */
    public val span: Span
}

/**
 * Trace identifier — the value that correlates all spans belonging to the same logical trace
 * across services. The encoding (hex, decimal, length) depends on the originating [TraceIdType].
 * Modelled as an inline value class so no wrapper is allocated at runtime.
 */
@JvmInline
public value class TraceId(public val value: String) {
    public companion object {
        /**
         * Sentinel "invalid" trace id — 32 hex zeros, matching the W3C spec reserved value.
         *
         * Exposed as `@JvmStatic` rather than `@JvmField`: Kotlin disallows `@JvmField`
         * on inline-class typed properties even though the JVM representation is just a
         * `String`. Java callers reach this via `TraceId.getNOOP()`.
         */
        @JvmStatic
        public val NOOP: TraceId = TraceId("00000000000000000000000000000000")
    }
}

/**
 * Span identifier — uniquely identifies a single span within its parent trace. Rendered as a
 * 16-character hex string per the W3C Trace Context spec. Modelled as an inline value class
 * so no wrapper is allocated at runtime.
 */
@JvmInline
public value class SpanId(public val value: String) {
    public companion object {
        /**
         * Sentinel "invalid" span id — 16 hex zeros, used when tracing is disabled or no
         * valid span is in scope.
         *
         * Exposed as `@JvmStatic` rather than `@JvmField`: Kotlin disallows `@JvmField`
         * on inline-class typed properties even though the JVM representation is just a
         * `String`. Java callers reach this via `SpanId.getNOOP()`.
         */
        @JvmStatic
        public val NOOP: SpanId = SpanId("0000000000000000")
    }
}

/**
 * W3C trace-flags — a two-character hex string carrying span-level bits such as the sampled
 * flag. Modelled as an inline value class so no wrapper is allocated at runtime.
 */
@JvmInline
public value class TraceFlags(public val value: String) {
    public companion object {
        /**
         * Sentinel "no flags set" trace-flags value — the W3C unsampled `"00"` byte.
         *
         * Exposed as `@JvmStatic` rather than `@JvmField`: Kotlin disallows `@JvmField`
         * on inline-class typed properties even though the JVM representation is just a
         * `String`. Java callers reach this via `TraceFlags.getNOOP()`.
         */
        @JvmStatic
        public val NOOP: TraceFlags = TraceFlags("00")
    }
}

/**
 * W3C trace-state — vendor-specific list of `key=value` pairs propagated alongside the trace
 * context, allowing multiple tracing systems to coexist. Modelled as an inline value class so
 * no wrapper is allocated at runtime.
 */
@JvmInline
public value class TraceState(public val value: String) {
    public companion object {
        /**
         * Sentinel "empty" trace state — no vendor-specific entries.
         *
         * Exposed as `@JvmStatic` rather than `@JvmField`: Kotlin disallows `@JvmField`
         * on inline-class typed properties even though the JVM representation is just a
         * `String`. Java callers reach this via `TraceState.getNOOP()`.
         */
        @JvmStatic
        public val NOOP: TraceState = TraceState("")
    }
}
