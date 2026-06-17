/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RawJsonTest {
    // ----- Obj -----

    @Test
    fun `Obj preserves insertion order and exposes read-only views`() {
        val obj =
            RawJson.Obj.of(
                linkedMapOf(
                    "b" to RawJson.Str("1"),
                    "a" to RawJson.Str("2"),
                ),
            )
        assertEquals(listOf("b", "a"), obj.keys.toList())
        assertEquals(2, obj.size)
        assertEquals(RawJson.Str("1"), obj["b"])
        assertTrue("a" in obj)
        assertFalse("z" in obj)
        assertNull(obj["z"])
    }

    @Test
    fun `Obj of empty returns the EMPTY singleton`() {
        assertSame(RawJson.Obj.EMPTY, RawJson.Obj.of(emptyMap()))
        assertTrue(RawJson.Obj.EMPTY.isEmpty())
    }

    @Test
    fun `Obj defensively copies its input`() {
        val mutable = linkedMapOf<String, RawJson>("k" to RawJson.Bool.TRUE)
        val obj = RawJson.Obj.of(mutable)
        mutable["k"] = RawJson.Bool.FALSE
        mutable["new"] = RawJson.Null
        assertEquals(RawJson.Bool.TRUE, obj["k"])
        assertEquals(1, obj.size)
    }

    @Test
    fun `Obj equality is structural`() {
        val a = RawJson.Obj.of(mapOf("k" to RawJson.Num("1")))
        val b = RawJson.Obj.of(mapOf("k" to RawJson.Num("1")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ----- Arr -----

    @Test
    fun `Arr exposes ordered read-only values`() {
        val arr = RawJson.Arr.of(listOf(RawJson.Num("1"), RawJson.Num("2")))
        assertEquals(2, arr.size)
        assertEquals(RawJson.Num("2"), arr[1])
        assertEquals(listOf(RawJson.Num("1"), RawJson.Num("2")), arr.values)
    }

    @Test
    fun `Arr of empty returns the EMPTY singleton`() {
        assertSame(RawJson.Arr.EMPTY, RawJson.Arr.of(emptyList()))
        assertTrue(RawJson.Arr.EMPTY.isEmpty())
    }

    @Test
    fun `Arr defensively copies its input`() {
        val mutable = mutableListOf<RawJson>(RawJson.Str("x"))
        val arr = RawJson.Arr.of(mutable)
        mutable.add(RawJson.Str("y"))
        assertEquals(1, arr.size)
    }

    // ----- Num fidelity -----

    @Test
    fun `Num preserves a large long id without precision loss`() {
        val literal = "9007199254740993" // 2^53 + 1, lossy as a Double
        val num = RawJson.Num(literal)
        assertEquals(literal, num.literal)
        assertEquals(9007199254740993L, num.toLongOrNull())
        assertEquals(BigDecimal(literal), num.toBigDecimal())
    }

    @Test
    fun `Num typed accessors return null for out-of-range or non-integer literals`() {
        assertNull(RawJson.Num("1.5").toLongOrNull())
        assertNull(RawJson.Num("1.5").toIntOrNull())
        assertNull(RawJson.Num("99999999999999999999").toLongOrNull())
        assertEquals(1.5, RawJson.Num("1.5").toDouble())
    }

    @Test
    fun `Num factories render canonical literals`() {
        assertEquals(RawJson.Num("42"), RawJson.Num.of(42))
        assertEquals(RawJson.Num("42"), RawJson.Num.of(42L))
        assertEquals(RawJson.Num("1.50"), RawJson.Num.of(BigDecimal("1.50")))
    }

    // ----- Bool / Null -----

    @Test
    fun `Bool of returns the matching singleton`() {
        assertSame(RawJson.Bool.TRUE, RawJson.Bool.of(true))
        assertSame(RawJson.Bool.FALSE, RawJson.Bool.of(false))
    }

    @Test
    fun `Null is a singleton and reports isNull`() {
        assertTrue(RawJson.Null.isNull)
        assertFalse(RawJson.Str("x").isNull)
        assertEquals("null", RawJson.Null.toString())
    }

    // ----- toString rendering -----

    @Test
    fun `toString renders a JSON-shaped preview with escaping`() {
        val tree =
            RawJson.Obj.of(
                linkedMapOf(
                    "name" to RawJson.Str("a\"b\nc"),
                    "list" to RawJson.Arr.of(listOf(RawJson.Num("1"), RawJson.Bool.TRUE, RawJson.Null)),
                ),
            )
        assertEquals("""{"name":"a\"b\nc", "list":[1, true, null]}""", tree.toString())
    }
}
