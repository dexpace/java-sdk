package org.dexpace.sdk.core.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies that [InstrumentationContext] exposes an [HttpTracerFactory] with a no-op
 * default, and that downstream implementations can override the factory without
 * touching the rest of the context surface.
 *
 *  1. Default `InstrumentationContext` (i.e. one that does not override the property)
 *     returns [NoopHttpTracerFactory] via the interface default getter.
 *  2. [NoopInstrumentationContext] returns the same singleton explicitly.
 *  3. A custom `InstrumentationContext` can override the factory to wire in a real
 *     tracer pipeline.
 */
class InstrumentationContextTracerTest {
    @Test
    fun `default InstrumentationContext has NoopHttpTracerFactory via the interface default`() {
        // Minimal implementation that does NOT override httpTracerFactory — verifies the
        // interface-default value is reachable without redeclaring the property. This
        // covers the "non-breaking change" guarantee of WU-7: existing implementations
        // compile and behave as if they always had a no-op tracer factory.
        val ctx: InstrumentationContext =
            object : InstrumentationContext {
                override val traceIdType: TraceIdType = TraceIdType.NOOP
                override val traceId: TraceId = TraceId.NOOP
                override val spanId: SpanId = SpanId.NOOP
                override val traceFlags: TraceFlags = TraceFlags.NOOP
                override val traceState: TraceState = TraceState.NOOP
                override val isValid: Boolean = false
                override val isRemote: Boolean = false
                override val span: Span = Span.NOOP
            }

        assertSame(NoopHttpTracerFactory, ctx.httpTracerFactory)
        // And the factory still produces a no-op tracer.
        assertSame(NoopHttpTracer, ctx.httpTracerFactory.newTracer(null, emptyMap()))
    }

    @Test
    fun `NoopInstrumentationContext exposes NoopHttpTracerFactory explicitly`() {
        // The singleton overrides the property (rather than inheriting it) so the no-op
        // identity is part of the contract. Asserting the singleton catches a refactor
        // that swaps the default factory for something allocating.
        assertSame(NoopHttpTracerFactory, NoopInstrumentationContext.httpTracerFactory)
    }

    @Test
    fun `NoopHttpTracerFactory returns NoopHttpTracer for every call`() {
        // Covers the SAM/lambda surface of HttpTracerFactory: regardless of operation
        // name (null or non-null) and attribute set (empty or populated) the no-op
        // factory must yield the shared singleton.
        assertSame(NoopHttpTracer, NoopHttpTracerFactory.newTracer(null, emptyMap()))
        assertSame(NoopHttpTracer, NoopHttpTracerFactory.newTracer("GetWidget", emptyMap()))
        assertSame(
            NoopHttpTracer,
            NoopHttpTracerFactory.newTracer("GetWidget", mapOf("service" to "widgets", "region" to "us-east-1")),
        )
    }

    @Test
    fun `custom InstrumentationContext can override httpTracerFactory`() {
        // A custom factory captures the operation name and attribute set so the test can
        // confirm that a real backend would receive both. This is the contract the WU-3
        // retry step and transport adapters will rely on.
        var lastName: String? = null
        var lastAttrs: Map<String, String>? = null
        val recordingFactory =
            HttpTracerFactory { operationName, attributes ->
                lastName = operationName
                lastAttrs = attributes
                NoopHttpTracer
            }

        val ctx: InstrumentationContext =
            object : InstrumentationContext {
                override val traceIdType: TraceIdType = TraceIdType.NOOP
                override val traceId: TraceId = TraceId.NOOP
                override val spanId: SpanId = SpanId.NOOP
                override val traceFlags: TraceFlags = TraceFlags.NOOP
                override val traceState: TraceState = TraceState.NOOP
                override val isValid: Boolean = false
                override val isRemote: Boolean = false
                override val span: Span = Span.NOOP
                override val httpTracerFactory: HttpTracerFactory = recordingFactory
            }

        val tracer = ctx.httpTracerFactory.newTracer("CreateOrder", mapOf("env" to "prod"))
        assertSame(NoopHttpTracer, tracer)
        assertEquals("CreateOrder", lastName)
        assertEquals(mapOf("env" to "prod"), lastAttrs)
    }

    @Test
    fun `custom factory can pass through a null operation name`() {
        // Raw HttpClient.execute() with no operation context must still produce a tracer.
        // The factory contract permits operationName = null.
        var captured: String? = "sentinel"
        val factory =
            HttpTracerFactory { operationName, _ ->
                captured = operationName
                NoopHttpTracer
            }
        factory.newTracer(null, emptyMap())
        assertNull(captured)
    }
}
