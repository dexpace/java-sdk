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
import kotlin.test.assertSame

class RequestContextTest {
    private val ownedIds: MutableList<String> = mutableListOf()

    @AfterTest
    fun evict() {
        for (id in ownedIds) ContextStore.remove(id)
    }

    private fun owned(name: String): String = "request-$name-${System.nanoTime()}".also { ownedIds.add(it) }

    @Test
    fun `constructor wires instrumentation context and request`() {
        val instr = FakeInstrumentationContext(TraceId(owned("ctor")))
        val req = request()
        val ctx = RequestContext(instr, req)
        assertSame(instr, ctx.instrumentationContext)
        assertSame(req, ctx.request)
    }

    @Test
    fun `toExchangeContext promotes with response and stores in ContextStore`() {
        val id = owned("promote")
        val instr = FakeInstrumentationContext(TraceId(id))
        val req = request()
        val resp = response()
        val parent = RequestContext(instr, req)
        ownedIds.add(parent.callKey)

        val promoted = parent.toExchangeContext(resp)

        assertSame(instr, promoted.instrumentationContext)
        assertSame(req, promoted.request)
        assertSame(resp, promoted.response)
        // Promotion carries the call key forward and registers the new context under it.
        assertEquals(parent.callKey, promoted.callKey)
        assertSame(promoted, ContextStore.get(promoted.callKey))
    }

    @Test
    fun `toExchangeContext carries the operation name forward`() {
        val instr = FakeInstrumentationContext(TraceId(owned("opname")))
        val parent = RequestContext(instr, request(), operationName = "GetUser")
        ownedIds.add(parent.callKey)

        val promoted = parent.toExchangeContext(response())

        assertEquals("GetUser", promoted.operationName)
    }

    @Test
    fun `data class equality is by content`() {
        val instr = FakeInstrumentationContext(TraceId(owned("eq")))
        val req = request()
        // Pin an explicit call key so the two instances are constructed identically: the
        // default key is now call-unique, so two default-keyed instances are deliberately
        // distinct (see the call-key uniqueness test below).
        val key = owned("eq-key")
        val a = RequestContext(instr, req, key)
        val b = RequestContext(instr, req, key)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a.copy())
    }

    @Test
    fun `two directly-constructed contexts sharing a trace and span id receive distinct call keys`() {
        // A directly-constructed request context — built off-chain from instrumentation that
        // shares a trace/span id (an inbound W3C trace, or a tracer reusing a span id across
        // sibling calls) — must still get a call-unique key. FakeInstrumentationContext defaults
        // to a fixed span id, so both instances below share the SAME trace id AND span id: the
        // exact collision case. The default call key must distinguish them, or they would clobber
        // each other in ContextStore (which rejects duplicate keys).
        val sharedId = owned("collision")
        val a = RequestContext(FakeInstrumentationContext(TraceId(sharedId)), request())
        val b = RequestContext(FakeInstrumentationContext(TraceId(sharedId)), request())
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
    fun `close evicts entry keyed by call key`() {
        val id = owned("close")
        val instr = FakeInstrumentationContext(TraceId(id))
        val ctx = RequestContext(instr, request())
        ownedIds.add(ctx.callKey)
        ContextStore.set(ctx.callKey, ctx)
        ctx.close()
        assertEquals(null, ContextStore.get(ctx.callKey))
    }
}
