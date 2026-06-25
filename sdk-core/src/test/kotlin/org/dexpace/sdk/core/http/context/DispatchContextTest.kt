/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext
import org.dexpace.sdk.core.instrumentation.TraceId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DispatchContextTest {
    private val ownedIds: MutableList<String> = mutableListOf()

    @AfterTest
    fun evict() {
        for (id in ownedIds) ContextStore.remove(id)
    }

    private fun owned(name: String): String = "dispatch-$name-${System.nanoTime()}".also { ownedIds.add(it) }

    @Test
    fun `constructor wires the instrumentation context`() {
        val instr = FakeInstrumentationContext(TraceId(owned("ctor")))
        val ctx = DispatchContext(instr)
        assertSame(instr, ctx.instrumentationContext)
    }

    @Test
    fun `toRequestContext returns a RequestContext with the same trace id, call key, and request`() {
        val id = owned("promote")
        val instr = FakeInstrumentationContext(TraceId(id))
        val dispatch = DispatchContext(instr)
        val req = request()
        ownedIds.add(dispatch.callKey)

        val promoted = dispatch.toRequestContext(req)

        assertSame(instr, promoted.instrumentationContext)
        assertSame(req, promoted.request)
        // Promotion carries the dispatch context's call key forward unchanged.
        assertEquals(dispatch.callKey, promoted.callKey)
        // Promotion registers the new context under the chain's call key.
        assertSame(promoted, ContextStore.get(promoted.callKey))
    }

    @Test
    fun `default factory returns a context with the NOOP instrumentation context`() {
        val ctx = DispatchContext.default()
        assertSame(NoopInstrumentationContext, ctx.instrumentationContext)
    }

    @Test
    fun `data class equality and copy work on instrumentation context`() {
        val instr = FakeInstrumentationContext(TraceId(owned("eq")))
        // Pin an explicit call key so the two instances are constructed identically: the
        // default key is now call-unique, so two default-keyed instances are deliberately
        // distinct (see the call-key uniqueness test below).
        val key = owned("eq-key")
        val a = DispatchContext(instr, key)
        val b = DispatchContext(instr, key)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val copy = a.copy()
        assertEquals(a, copy)
    }

    @Test
    fun `two contexts sharing a trace and span id receive distinct call keys`() {
        // A directly-constructed dispatch context — built from an inbound W3C trace, or by a
        // tracer that reuses a span id across sibling client calls — must still get a
        // call-unique key. FakeInstrumentationContext defaults to a fixed span id, so both
        // instances below share the SAME trace id AND span id: the exact collision case. The
        // default call key must distinguish them, or they would clobber each other in
        // ContextStore (which rejects duplicate keys).
        val sharedId = owned("collision")
        val a = DispatchContext(FakeInstrumentationContext(TraceId(sharedId)))
        val b = DispatchContext(FakeInstrumentationContext(TraceId(sharedId)))
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
        val dispatch = DispatchContext(instr)
        ownedIds.add(dispatch.callKey)
        // Put the context into the store first via `set` so close has something to evict.
        ContextStore.set(dispatch.callKey, dispatch)
        assertNotNull(ContextStore.get(dispatch.callKey))
        dispatch.close()
        assertEquals(null, ContextStore.get(dispatch.callKey))
    }

    @Test
    fun `default generates a unique call key per invocation so concurrent untraced calls stay independent`() {
        val a = DispatchContext.default()
        val b = DispatchContext.default()
        ownedIds.add(a.callKey)
        ownedIds.add(b.callKey)

        // Both use the shared no-op instrumentation context, but their store keys differ.
        assertSame(a.instrumentationContext, b.instrumentationContext)
        assertNotEquals(a.callKey, b.callKey)

        ContextStore.set(a.callKey, a)
        ContextStore.set(b.callKey, b)
        assertSame(a, ContextStore.get(a.callKey))
        assertSame(b, ContextStore.get(b.callKey))

        // Closing one leaves the other's entry intact — no cross-call eviction.
        a.close()
        assertEquals(null, ContextStore.get(a.callKey))
        assertSame(b, ContextStore.get(b.callKey))
    }

    @Test
    fun `closing a promoted-from dispatch context does not evict the live child`() {
        val id = owned("ownership")
        val instr = FakeInstrumentationContext(TraceId(id))
        val dispatch = DispatchContext(instr)
        ownedIds.add(dispatch.callKey)

        val child = dispatch.toRequestContext(request())
        assertSame(child, ContextStore.get(dispatch.callKey))

        // The parent is now an intermediate link; closing it must not delete the child,
        // which took over the (shared) call-key slot.
        dispatch.close()
        assertSame(child, ContextStore.get(dispatch.callKey))

        // Closing the terminal child evicts the chain.
        child.close()
        assertEquals(null, ContextStore.get(dispatch.callKey))
    }
}
