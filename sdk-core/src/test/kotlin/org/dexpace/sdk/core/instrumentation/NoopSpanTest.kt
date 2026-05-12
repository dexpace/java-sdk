package org.dexpace.sdk.core.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Verifies every method on the shared [NoopSpan] singleton. The point of the no-op
 * span is that every call is allocation-free and side-effect-free; we assert the
 * "returns this", "returns a shared singleton", and "no throw" properties exactly
 * because those are the load-bearing contracts external callers rely on when
 * tracing is disabled.
 */
class NoopSpanTest {

    @Test
    fun `isRecording is always false`() {
        assertFalse(NoopSpan.isRecording)
        // Same property reached through the Span.NOOP alias.
        assertFalse(Span.NOOP.isRecording)
    }

    @Test
    fun `context is the NoopInstrumentationContext singleton`() {
        assertSame(NoopInstrumentationContext, NoopSpan.context)
        assertSame(NoopInstrumentationContext, Span.NOOP.context)
    }

    @Test
    fun `setAttribute returns this for fluent chaining`() {
        val result = NoopSpan.setAttribute("k", "v")
        assertSame(NoopSpan, result)
    }

    @Test
    fun `setAttribute accepts any value type without throwing`() {
        // Non-string values must not throw — the Any contract on the interface is
        // honoured by the no-op even though the value is dropped.
        NoopSpan.setAttribute("int", 1)
        NoopSpan.setAttribute("bool", true)
        NoopSpan.setAttribute("list", listOf(1, 2, 3))
    }

    @Test
    fun `setError returns this for fluent chaining`() {
        val result = NoopSpan.setError("MyError")
        assertSame(NoopSpan, result)
    }

    @Test
    fun `makeCurrent returns a cached TracingScope singleton`() {
        // NoopSpan documents that the returned scope is shared — assert identity
        // across calls so a regression to "new SAM each call" is caught.
        val first = NoopSpan.makeCurrent()
        val second = NoopSpan.makeCurrent()
        assertSame(first, second)
    }

    @Test
    fun `makeCurrent close is a no-op and idempotent`() {
        val scope = NoopSpan.makeCurrent()
        // Multiple closes must not throw; the scope is shared so anything else would
        // be a serious correctness bug.
        scope.close()
        scope.close()
        scope.close()
    }

    @Test
    fun `end does not throw`() {
        NoopSpan.end()
        // Calling end twice is documented as a no-op; verify.
        NoopSpan.end()
    }

    @Test
    fun `end with throwable does not throw and swallows the cause`() {
        NoopSpan.end(RuntimeException("ignored"))
        NoopSpan.end(IllegalStateException("also ignored"))
    }

    @Test
    fun `Span NOOP is the NoopSpan instance`() {
        assertSame(NoopSpan, Span.NOOP)
    }

    @Test
    fun `fluent chain via setAttribute then setError then end compiles and works`() {
        // Smoke-tests the full fluent surface as a single chain — verifies no
        // intermediate state is hidden behind these methods.
        val span: Span = Span.NOOP
        span.setAttribute("a", 1).setError("Boom").end()
        assertEquals(false, span.isRecording)
    }
}
