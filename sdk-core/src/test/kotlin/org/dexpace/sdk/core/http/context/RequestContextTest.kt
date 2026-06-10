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
    fun `data class equality is by content`() {
        val instr = FakeInstrumentationContext(TraceId(owned("eq")))
        val req = request()
        val a = RequestContext(instr, req)
        val b = RequestContext(instr, req)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a.copy())
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
