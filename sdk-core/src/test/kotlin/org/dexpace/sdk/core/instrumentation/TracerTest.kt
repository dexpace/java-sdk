/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the [Tracer] interface's default-parameter `attributes` path — the
 * Kotlin-only one-arg invocation that delegates to the two-arg method with
 * `emptyMap()`. Exercises both [NoopTracer] and a hand-rolled implementation
 * to confirm the default is plumbed through correctly.
 */
class TracerTest {
    @Test
    fun `NoopTracer startSpan default attributes returns Span NOOP`() {
        // Single-arg form: covers the default-parameter `attributes = emptyMap()` path.
        val span = NoopTracer.startSpan("op")
        assertSame(Span.NOOP, span)
    }

    @Test
    fun `NoopTracer startSpan with explicit attributes returns Span NOOP`() {
        val span = NoopTracer.startSpan("op", mapOf("k" to "v"))
        assertSame(Span.NOOP, span)
    }

    @Test
    fun `custom Tracer implementation receives empty map when attributes omitted`() {
        // Hand-rolled implementation records what it saw so the test can assert the
        // default parameter actually resolved to an empty map.
        val captured = mutableListOf<Pair<String, Map<String, Any>>>()
        val tracer =
            object : Tracer {
                override fun startSpan(
                    name: String,
                    attributes: Map<String, Any>,
                ): Span {
                    captured.add(name to attributes)
                    return Span.NOOP
                }
            }

        // Kotlin-side single-arg invocation goes through the default parameter.
        tracer.startSpan("op-default")
        assertEquals(1, captured.size)
        assertEquals("op-default", captured.single().first)
        assertTrue(captured.single().second.isEmpty(), "expected default empty map")

        // Explicit attributes path still works.
        tracer.startSpan("op-explicit", mapOf("a" to 1))
        assertEquals(2, captured.size)
        assertEquals(mapOf("a" to 1), captured.last().second)
    }

    @Test
    fun `custom Tracer can return its own Span impl`() {
        // Confirms the Tracer SPI returns Span via the abstract interface — not a
        // hidden cast to NoopSpan or similar.
        val recorded = mutableListOf<String>()
        val customSpan: Span =
            object : Span {
                override val isRecording: Boolean = true
                override val context: InstrumentationContext = NoopInstrumentationContext

                override fun setAttribute(
                    key: String,
                    value: Any,
                ): Span {
                    recorded.add("$key=$value")
                    return this
                }

                override fun setError(errorType: String): Span = this

                override fun makeCurrent(): TracingScope = TracingScope { }

                override fun end() = Unit

                override fun end(throwable: Throwable) = Unit
            }
        val tracer: Tracer =
            object : Tracer {
                override fun startSpan(
                    name: String,
                    attributes: Map<String, Any>,
                ): Span = customSpan
            }

        val span = tracer.startSpan("op")
        assertSame(customSpan, span)
        assertNotNull(span.setAttribute("a", 1))
        assertEquals(listOf("a=1"), recorded)
    }

    @Test
    fun `Tracer object form works with default attributes`() {
        // The single-arg default-parameter call site must dispatch correctly to a
        // custom Tracer impl. Captures the resolved attributes map to confirm the
        // default (emptyMap) plumbed through.
        var lastName: String? = null
        var lastAttrs: Map<String, Any>? = null
        val tracer: Tracer =
            object : Tracer {
                override fun startSpan(
                    name: String,
                    attributes: Map<String, Any>,
                ): Span {
                    lastName = name
                    lastAttrs = attributes
                    return Span.NOOP
                }
            }
        tracer.startSpan("hello")
        assertEquals("hello", lastName)
        assertEquals(emptyMap(), lastAttrs)
    }
}
