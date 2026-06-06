/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Verifies every property on [NoopInstrumentationContext]. The no-op context exists
 * to provide a consistent, side-effect-free [InstrumentationContext] when tracing is
 * disabled; each of its properties has a documented sentinel value that downstream
 * propagators rely on to detect "no trace here".
 */
class NoopInstrumentationContextTest {
    @Test
    fun `traceIdType is NOOP`() {
        // TraceIdType is a regular enum, so identity holds across reads.
        assertSame(TraceIdType.NOOP, NoopInstrumentationContext.traceIdType)
    }

    @Test
    fun `traceId is TraceId NOOP - 32 hex zeros`() {
        // TraceId is an @JvmInline value class — comparing the boxed wrappers via assertSame
        // does not hold, so assert structural equality + the underlying value.
        assertEquals(TraceId.NOOP, NoopInstrumentationContext.traceId)
        assertEquals("00000000000000000000000000000000", NoopInstrumentationContext.traceId.value)
    }

    @Test
    fun `spanId is SpanId NOOP - 16 hex zeros`() {
        assertEquals(SpanId.NOOP, NoopInstrumentationContext.spanId)
        assertEquals("0000000000000000", NoopInstrumentationContext.spanId.value)
    }

    @Test
    fun `traceFlags is TraceFlags NOOP - the W3C unsampled 00 byte`() {
        assertEquals(TraceFlags.NOOP, NoopInstrumentationContext.traceFlags)
        assertEquals("00", NoopInstrumentationContext.traceFlags.value)
    }

    @Test
    fun `traceState is TraceState NOOP - empty string`() {
        assertEquals(TraceState.NOOP, NoopInstrumentationContext.traceState)
        assertEquals("", NoopInstrumentationContext.traceState.value)
    }

    @Test
    fun `isValid is false`() {
        assertFalse(NoopInstrumentationContext.isValid)
    }

    @Test
    fun `isRemote is false`() {
        assertFalse(NoopInstrumentationContext.isRemote)
    }

    @Test
    fun `span is Span NOOP`() {
        assertSame(Span.NOOP, NoopInstrumentationContext.span)
    }

    @Test
    fun `singleton identity holds across calls`() {
        // It's a Kotlin object — there can be only one instance — but assert it
        // explicitly so a hypothetical refactor to a class trips this test.
        val a: InstrumentationContext = NoopInstrumentationContext
        val b: InstrumentationContext = NoopInstrumentationContext
        assertSame(a, b)
    }
}
