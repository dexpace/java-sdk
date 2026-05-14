package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.TraceId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ExchangeContextTest {
    private val ownedIds: MutableList<String> = mutableListOf()

    @AfterTest
    fun evict() {
        for (id in ownedIds) ContextStore.remove(id)
    }

    private fun owned(name: String): String = "exchange-$name-${System.nanoTime()}".also { ownedIds.add(it) }

    @Test
    fun `constructor wires every field`() {
        val instr = FakeInstrumentationContext(TraceId(owned("ctor")))
        val req = request()
        val resp = response()
        val ctx = ExchangeContext(instr, req, resp)
        assertSame(instr, ctx.instrumentationContext)
        assertSame(req, ctx.request)
        assertSame(resp, ctx.response)
    }

    @Test
    fun `data class equality is by content`() {
        val instr = FakeInstrumentationContext(TraceId(owned("eq")))
        val req = request()
        val resp = response()
        val a = ExchangeContext(instr, req, resp)
        val b = ExchangeContext(instr, req, resp)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy with same fields is equal to original`() {
        val instr = FakeInstrumentationContext(TraceId(owned("copy-equal")))
        val ctx = ExchangeContext(instr, request(), response())
        assertEquals(ctx, ctx.copy())
    }

    @Test
    fun `copy with different request differs from the original`() {
        val instr = FakeInstrumentationContext(TraceId(owned("copy-diff")))
        val req1 = request()
        val req2 = request().newBuilder().addHeader("X-Extra", "1").build()
        val original = ExchangeContext(instr, req1, response())
        val changed = original.copy(request = req2)
        assertNotEquals(original, changed)
    }

    @Test
    fun `close evicts entry keyed by trace id`() {
        val id = owned("close")
        val instr = FakeInstrumentationContext(TraceId(id))
        val ctx = ExchangeContext(instr, request(), response())
        ContextStore.set(id, ctx)
        ctx.close()
        assertNull(ContextStore.get(id))
    }
}
