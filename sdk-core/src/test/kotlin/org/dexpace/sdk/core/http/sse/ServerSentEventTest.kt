/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerSentEventTest {
    @Test
    fun `data is defensively copied so later mutation of the source list does not leak in`() {
        val source = mutableListOf("a", "b")
        val event = ServerSentEvent(data = source)
        // Mutating the original list after construction must not affect the event.
        source.add("c")
        source[0] = "MUTATED"
        assertEquals(listOf("a", "b"), event.data)
    }

    @Test
    fun `copy also defensively copies its data argument`() {
        val original = ServerSentEvent(id = "1", data = listOf("a"))
        val replacement = mutableListOf("b", "c")
        val copied = original.copy(data = replacement)
        replacement.add("d")
        assertEquals(listOf("b", "c"), copied.data)
    }

    @Test
    fun `equals and hashCode honour all five fields including data`() {
        val a =
            ServerSentEvent(id = "1", event = "msg", data = listOf("x"), comment = "c", retry = Duration.ofMillis(5))
        val b =
            ServerSentEvent(id = "1", event = "msg", data = listOf("x"), comment = "c", retry = Duration.ofMillis(5))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // Differing data must break equality (proves data participates in value semantics).
        val differentData =
            ServerSentEvent(id = "1", event = "msg", data = listOf("y"), comment = "c", retry = Duration.ofMillis(5))
        assertNotEquals(a, differentData)
    }

    @Test
    @Suppress("EqualsNullCall")
    fun `equals distinguishes each field`() {
        val base =
            ServerSentEvent(id = "1", event = "e", data = listOf("d"), comment = "c", retry = Duration.ofMillis(1))
        assertNotEquals(base, base.copy(id = "2"))
        assertNotEquals(base, base.copy(event = "other"))
        assertNotEquals(base, base.copy(data = listOf("other")))
        assertNotEquals(base, base.copy(comment = "other"))
        assertNotEquals(base, base.copy(retry = Duration.ofMillis(2)))
        // Self-equality and null/other-type inequality.
        assertEquals(base, base)
        assertFalse(base.equals(null))
        assertFalse(base.equals("not an event"))
    }

    @Test
    @Suppress("DestructuringDeclarationWithTooManyEntries")
    fun `component functions destructure in declaration order`() {
        val event =
            ServerSentEvent(
                id = "1",
                event = "msg",
                data = listOf("a", "b"),
                comment = "c",
                retry = Duration.ofMillis(7),
            )
        val (id, type, data, comment, retry) = event
        assertEquals("1", id)
        assertEquals("msg", type)
        assertEquals(listOf("a", "b"), data)
        assertEquals("c", comment)
        assertEquals(Duration.ofMillis(7), retry)
    }

    @Test
    fun `copy without arguments preserves all fields`() {
        val event =
            ServerSentEvent(
                id = "1",
                event = "msg",
                data = listOf("a"),
                comment = "c",
                retry = Duration.ofMillis(3),
            )
        assertEquals(event, event.copy())
    }

    @Test
    fun `copy replaces a single field and leaves the rest intact`() {
        val event = ServerSentEvent(id = "1", event = "msg", data = listOf("a"))
        val updated = event.copy(id = "2")
        assertEquals("2", updated.id)
        assertEquals("msg", updated.event)
        assertEquals(listOf("a"), updated.data)
    }

    @Test
    fun `toString lists every field`() {
        val event =
            ServerSentEvent(id = "1", event = "msg", data = listOf("a"), comment = "c", retry = Duration.ofMillis(2))
        val rendered = event.toString()
        assertTrue(rendered.startsWith("ServerSentEvent("))
        assertTrue(rendered.contains("id=1"))
        assertTrue(rendered.contains("event=msg"))
        assertTrue(rendered.contains("data=[a]"))
        assertTrue(rendered.contains("comment=c"))
        assertTrue(rendered.contains("retry="))
    }

    @Test
    fun `default-argument constructor yields an empty event`() {
        val event = ServerSentEvent()
        assertTrue(event.isEmpty)
        assertEquals(emptyList(), event.data)
    }
}
