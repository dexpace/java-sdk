/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpanLoggingExtensionsTest {
    @BeforeTest
    fun installMdcAdapter() {
        // The test runtime uses slf4j-nop whose MDCAdapter is a no-op. MDC.setMDCAdapter
        // is package-private, so we install BasicMDCAdapter (in slf4j-api) via reflection.
        installBasicMdcAdapter()
    }

    @AfterTest
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `makeCurrentWithLoggingContext pushes trace and span ids onto MDC`() {
        val span = recordingTestSpan(traceId = "abc", spanId = "xyz")
        span.makeCurrentWithLoggingContext().use {
            assertEquals("abc", MDC.get("trace.id"))
            assertEquals("xyz", MDC.get("span.id"))
        }
        assertNull(MDC.get("trace.id"))
        assertNull(MDC.get("span.id"))
    }

    @Test
    fun `non-recording span skips MDC push`() {
        val span = recordingTestSpan(traceId = "abc", spanId = "xyz", recording = false)
        span.makeCurrentWithLoggingContext().use {
            assertNull(MDC.get("trace.id"))
            assertNull(MDC.get("span.id"))
        }
    }

    @Test
    fun `non-recording span returns the exact scope object returned by makeCurrent`() {
        // A non-recording span whose makeCurrent() returns a distinct (non-no-op) scope object.
        // makeCurrentWithLoggingContext must return that SAME scope instance, not a wrapper.
        val stubScope =
            object : TracingScope {
                override fun close() {}
            }
        val span =
            object : Span {
                override val isRecording: Boolean = false
                override val context: InstrumentationContext = NoopInstrumentationContext

                override fun setAttribute(
                    key: String,
                    value: Any,
                ): Span = this

                override fun setError(errorType: String): Span = this

                override fun makeCurrent(): TracingScope = stubScope

                override fun end() = Unit

                override fun end(throwable: Throwable) = Unit
            }
        val returned = span.makeCurrentWithLoggingContext()
        // Must be the exact object from makeCurrent(), not wrapped.
        assertEquals(stubScope, returned, "expected the stub scope to be returned unwrapped")
    }

    @Test
    fun `nested scopes restore the outer MDC values on close`() {
        val outer = recordingTestSpan(traceId = "outer-trace", spanId = "outer-span")
        val inner = recordingTestSpan(traceId = "inner-trace", spanId = "inner-span")
        outer.makeCurrentWithLoggingContext().use {
            assertEquals("outer-trace", MDC.get("trace.id"))
            inner.makeCurrentWithLoggingContext().use {
                assertEquals("inner-trace", MDC.get("trace.id"))
            }
            assertEquals("outer-trace", MDC.get("trace.id"))
        }
        assertNull(MDC.get("trace.id"))
    }

    private fun recordingTestSpan(
        traceId: String,
        spanId: String,
        recording: Boolean = true,
    ): Span {
        // Build the context first with a placeholder span reference, then close over the
        // outer span in a separate variable to avoid a circular initializer.
        val ctx =
            object : InstrumentationContext {
                override val traceIdType: TraceIdType = TraceIdType.NOOP
                override val traceId: TraceId = TraceId(traceId)
                override val spanId: SpanId = SpanId(spanId)
                override val traceFlags: TraceFlags = TraceFlags.NOOP
                override val traceState: TraceState = TraceState.NOOP
                override val isValid: Boolean = true
                override val isRemote: Boolean = false

                // The span back-reference is unused by makeCurrentWithLoggingContext; use NOOP.
                override val span: Span = Span.NOOP
            }
        return object : Span {
            override val isRecording: Boolean = recording
            override val context: InstrumentationContext = ctx

            override fun setAttribute(
                key: String,
                value: Any,
            ): Span = this

            override fun setError(errorType: String): Span = this

            override fun makeCurrent(): TracingScope = TracingScope { /* no-op */ }

            override fun end() = Unit

            override fun end(throwable: Throwable) = Unit
        }
    }
}

/**
 * Installs [BasicMDCAdapter] as the active SLF4J MDC adapter via reflection.
 *
 * The test runtime uses `slf4j-nop`, which provides a no-op MDC adapter that silently
 * discards all `put` calls. `MDC.setMDCAdapter` is package-private, so reflection is used.
 * `BasicMDCAdapter` ships in `slf4j-api` itself (no extra dependency needed).
 */
internal fun installBasicMdcAdapter() {
    val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
    field.isAccessible = true
    field.set(null, BasicMDCAdapter())
}
