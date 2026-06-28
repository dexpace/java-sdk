/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdditionalPropertiesTest {
    @Test
    fun `of empty returns the EMPTY singleton`() {
        assertSame(AdditionalProperties.EMPTY, AdditionalProperties.of(emptyMap()))
        assertSame(AdditionalProperties.EMPTY, AdditionalProperties.empty())
        assertTrue(AdditionalProperties.EMPTY.isEmpty())
    }

    @Test
    fun `of preserves insertion order and exposes read-only views`() {
        val props =
            AdditionalProperties.of(
                linkedMapOf(
                    "z" to RawJson.Num("1"),
                    "a" to RawJson.Str("two"),
                ),
            )
        assertEquals(listOf("z", "a"), props.keys.toList())
        assertEquals(2, props.size)
        assertEquals(RawJson.Num("1"), props["z"])
        assertTrue("a" in props)
        assertFalse("missing" in props)
        assertNull(props["missing"])
    }

    @Test
    fun `of defensively copies its input`() {
        val mutable = linkedMapOf<String, RawJson>("k" to RawJson.Bool.TRUE)
        val props = AdditionalProperties.of(mutable)
        mutable["k"] = RawJson.Bool.FALSE
        mutable["new"] = RawJson.Null
        assertEquals(RawJson.Bool.TRUE, props["k"])
        assertEquals(1, props.size)
    }

    @Test
    fun `toRawJson renders the captured properties as an Obj`() {
        val props = AdditionalProperties.of(linkedMapOf("k" to RawJson.Str("v")))
        assertEquals(RawJson.Obj.of(mapOf("k" to RawJson.Str("v"))), props.toRawJson())
    }

    @Test
    fun `builder preserves put order and builds an immutable snapshot`() {
        val props =
            AdditionalProperties
                .builder()
                .put("first", RawJson.Num("1"))
                .put("second", RawJson.Num("2"))
                .build()
        assertEquals(listOf("first", "second"), props.keys.toList())
        assertEquals(RawJson.Num("2"), props["second"])
    }

    @Test
    fun `builder putAll remove and clear behave as expected`() {
        val built =
            AdditionalProperties
                .builder()
                .putAll(linkedMapOf("a" to RawJson.Null, "b" to RawJson.Null, "c" to RawJson.Null))
                .remove("b")
                .build()
        assertEquals(listOf("a", "c"), built.keys.toList())

        val cleared = built.newBuilder().clear().build()
        assertSame(AdditionalProperties.EMPTY, cleared)
    }

    @Test
    fun `newBuilder is prefilled and supports a derived modified copy`() {
        val original = AdditionalProperties.of(linkedMapOf("a" to RawJson.Str("1")))
        val derived = original.newBuilder().put("b", RawJson.Str("2")).build()
        // Original is unchanged (immutability) and the derived copy carries both keys in order.
        assertEquals(listOf("a"), original.keys.toList())
        assertEquals(listOf("a", "b"), derived.keys.toList())
    }

    @Test
    fun `put replaces an existing key without changing its position`() {
        val props =
            AdditionalProperties
                .builder()
                .put("a", RawJson.Num("1"))
                .put("b", RawJson.Num("2"))
                .put("a", RawJson.Num("9"))
                .build()
        assertEquals(listOf("a", "b"), props.keys.toList())
        assertEquals(RawJson.Num("9"), props["a"])
    }

    @Test
    fun `equality is structural`() {
        val a = AdditionalProperties.of(mapOf("k" to RawJson.Num("1")))
        val b = AdditionalProperties.of(mapOf("k" to RawJson.Num("1")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString renders the captured properties`() {
        val props = AdditionalProperties.of(linkedMapOf("k" to RawJson.Str("v")))
        assertEquals("""AdditionalProperties({"k":"v"})""", props.toString())
    }
}
