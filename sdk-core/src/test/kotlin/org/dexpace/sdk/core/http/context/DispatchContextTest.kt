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
    fun `toRequestContext returns a RequestContext with the same trace id and request`() {
        val id = owned("promote")
        val instr = FakeInstrumentationContext(TraceId(id))
        val dispatch = DispatchContext(instr)
        val req = request()

        val promoted = dispatch.toRequestContext(req)

        assertSame(instr, promoted.instrumentationContext)
        assertSame(req, promoted.request)
        // Promotion registers the new context under the trace id.
        assertSame(promoted, ContextStore.get(id))
    }

    @Test
    fun `default factory returns a context with the NOOP instrumentation context`() {
        val ctx = DispatchContext.default()
        assertSame(NoopInstrumentationContext, ctx.instrumentationContext)
    }

    @Test
    fun `data class equality and copy work on instrumentation context`() {
        val instr = FakeInstrumentationContext(TraceId(owned("eq")))
        val a = DispatchContext(instr)
        val b = DispatchContext(instr)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val copy = a.copy()
        assertEquals(a, copy)
    }

    @Test
    fun `close evicts entry keyed by trace id`() {
        val id = owned("close")
        val instr = FakeInstrumentationContext(TraceId(id))
        val dispatch = DispatchContext(instr)
        // Put the context into the store first via `set` so close has something to evict.
        ContextStore.set(id, dispatch)
        assertNotNull(ContextStore.get(id))
        dispatch.close()
        assertEquals(null, ContextStore.get(id))
    }
}
