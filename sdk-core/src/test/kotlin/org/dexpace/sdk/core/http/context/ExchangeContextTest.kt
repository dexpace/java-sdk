/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
        // Pin an explicit call key so the two instances are constructed identically: the
        // default key is now call-unique, so two default-keyed instances are deliberately
        // distinct (see the call-key uniqueness test below).
        val key = owned("eq-key")
        val a = ExchangeContext(instr, req, resp, key)
        val b = ExchangeContext(instr, req, resp, key)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two directly-constructed contexts sharing a trace and span id receive distinct call keys`() {
        // A directly-constructed exchange context — built off-chain from instrumentation that
        // shares a trace/span id (an inbound W3C trace, or a tracer reusing a span id across
        // sibling calls) — must still get a call-unique key. FakeInstrumentationContext defaults
        // to a fixed span id, so both instances below share the SAME trace id AND span id: the
        // exact collision case. The default call key must distinguish them, or they would clobber
        // each other in ContextStore (which rejects duplicate keys).
        val sharedId = owned("collision")
        val a = ExchangeContext(FakeInstrumentationContext(TraceId(sharedId)), request(), response())
        val b = ExchangeContext(FakeInstrumentationContext(TraceId(sharedId)), request(), response())
        ownedIds.add(a.callKey)
        ownedIds.add(b.callKey)

        assertEquals(a.instrumentationContext.traceId, b.instrumentationContext.traceId)
        assertEquals(a.instrumentationContext.spanId, b.instrumentationContext.spanId)
        assertNotEquals(a.callKey, b.callKey)

        // Both register in the store without one rejecting or evicting the other.
        ContextStore.put(a.callKey, a)
        ContextStore.put(b.callKey, b)
        assertSame(a, ContextStore.get(a.callKey))
        assertSame(b, ContextStore.get(b.callKey))
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
    fun `close evicts entry keyed by call key`() {
        val id = owned("close")
        val instr = FakeInstrumentationContext(TraceId(id))
        val ctx = ExchangeContext(instr, request(), response())
        ownedIds.add(ctx.callKey)
        ContextStore.set(ctx.callKey, ctx)
        ctx.close()
        assertNull(ContextStore.get(ctx.callKey))
    }
}
