/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.TraceId
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CallOptionsPropagationTest {
    private val ownedIds: MutableList<String> = mutableListOf()

    @AfterTest
    fun evict() {
        for (id in ownedIds) ContextStore.remove(id)
    }

    private fun owned(name: String): String = "options-$name-${System.nanoTime()}".also { ownedIds.add(it) }

    @Test
    fun `dispatch context defaults callOptions to NONE`() {
        val ctx = DispatchContext(FakeInstrumentationContext(TraceId(owned("default"))))
        ownedIds.add(ctx.callKey)
        assertSame(CallOptions.NONE, ctx.callOptions)
    }

    @Test
    fun `callOptions propagate unchanged through the promotion chain`() {
        val options =
            CallOptions.builder()
                .timeout(CallTimeout.ofCall(Duration.ofSeconds(5)))
                .responseValidation(ResponseValidation.DISABLED)
                .build()
        val dispatch =
            DispatchContext(
                instrumentationContext = FakeInstrumentationContext(TraceId(owned("propagate"))),
                callOptions = options,
            )
        ownedIds.add(dispatch.callKey)

        val requestCtx = dispatch.toRequestContext(request())
        val exchangeCtx = requestCtx.toExchangeContext(response())

        assertSame(options, requestCtx.callOptions)
        assertSame(options, exchangeCtx.callOptions)
    }

    @Test
    fun `request context carries callOptions when constructed off-chain`() {
        val options = CallOptions.builder().responseValidation(ResponseValidation.ENABLED).build()
        val key = owned("offchain")
        val ctx =
            RequestContext(
                instrumentationContext = FakeInstrumentationContext(TraceId(key)),
                request = request(),
                callKey = key,
                callOptions = options,
            )
        assertSame(options, ctx.callOptions)
    }

    @Test
    fun `data class equality includes callOptions`() {
        val key = owned("equality")
        val instr = FakeInstrumentationContext(TraceId(key))
        val withOptions =
            DispatchContext(instr, key, CallOptions.builder().responseValidation(ResponseValidation.DISABLED).build())
        val withNone = DispatchContext(instr, key, CallOptions.NONE)
        assertEquals(withNone, DispatchContext(instr, key))
        // Distinct call options make otherwise-identical contexts unequal.
        kotlin.test.assertNotEquals(withOptions, withNone)
    }
}
