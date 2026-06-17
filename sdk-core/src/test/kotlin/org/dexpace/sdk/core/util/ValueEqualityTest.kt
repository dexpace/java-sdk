/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ValueEqualityTest {
    // ---- null handling -------------------------------------------------------------------

    @Test
    fun `two nulls are equal and hash to zero`() {
        assertTrue(ValueEquality.contentEquals(null, null))
        assertEquals(0, ValueEquality.contentHashCode(null))
    }

    @Test
    fun `null and non-null are not equal in either order`() {
        assertFalse(ValueEquality.contentEquals(null, byteArrayOf(1)))
        assertFalse(ValueEquality.contentEquals(byteArrayOf(1), null))
    }

    // ---- identity shortcut ---------------------------------------------------------------

    @Test
    fun `same reference is equal`() {
        val array = intArrayOf(1, 2, 3)
        assertTrue(ValueEquality.contentEquals(array, array))
    }

    // ---- scalars / non-arrays ------------------------------------------------------------

    @Test
    fun `non-array values use ordinary equals and hashCode`() {
        assertTrue(ValueEquality.contentEquals("abc", "abc"))
        assertFalse(ValueEquality.contentEquals("abc", "xyz"))
        assertEquals("abc".hashCode(), ValueEquality.contentHashCode("abc"))

        assertTrue(ValueEquality.contentEquals(42, 42))
        assertEquals(42.hashCode(), ValueEquality.contentHashCode(42))
    }

    // ---- primitive arrays ----------------------------------------------------------------

    @Test
    fun `byte arrays compare by content`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3)
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        assertFalse(ValueEquality.contentEquals(a, byteArrayOf(1, 2, 4)))
        assertFalse(ValueEquality.contentEquals(a, byteArrayOf(1, 2)))
    }

    @Test
    fun `each primitive array kind compares by content`() {
        assertContentEqualPair(booleanArrayOf(true, false), booleanArrayOf(true, false))
        assertContentEqualPair(charArrayOf('a', 'b'), charArrayOf('a', 'b'))
        assertContentEqualPair(shortArrayOf(1, 2), shortArrayOf(1, 2))
        assertContentEqualPair(intArrayOf(1, 2), intArrayOf(1, 2))
        assertContentEqualPair(longArrayOf(1L, 2L), longArrayOf(1L, 2L))
        assertContentEqualPair(floatArrayOf(1.5f, 2.5f), floatArrayOf(1.5f, 2.5f))
        assertContentEqualPair(doubleArrayOf(1.5, 2.5), doubleArrayOf(1.5, 2.5))
    }

    @Test
    fun `differing primitive arrays are not equal`() {
        assertFalse(ValueEquality.contentEquals(intArrayOf(1, 2), intArrayOf(1, 3)))
        assertFalse(ValueEquality.contentEquals(longArrayOf(1L), longArrayOf(2L)))
        assertFalse(ValueEquality.contentEquals(doubleArrayOf(1.0), doubleArrayOf(2.0)))
    }

    // ---- object arrays -------------------------------------------------------------------

    @Test
    fun `object arrays compare by content`() {
        val a = arrayOf("x", "y", "z")
        val b = arrayOf("x", "y", "z")
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        assertFalse(ValueEquality.contentEquals(a, arrayOf("x", "y")))
    }

    @Test
    fun `object arrays containing nulls compare by content`() {
        val a = arrayOf("x", null, "z")
        val b = arrayOf("x", null, "z")
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        assertFalse(ValueEquality.contentEquals(a, arrayOf("x", "y", "z")))
    }

    // ---- nested / multi-dimensional arrays ----------------------------------------------

    @Test
    fun `nested object arrays compare structurally`() {
        val a = arrayOf(arrayOf("a", "b"), arrayOf("c"))
        val b = arrayOf(arrayOf("a", "b"), arrayOf("c"))
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        val different = arrayOf(arrayOf("a", "b"), arrayOf("d"))
        assertFalse(ValueEquality.contentEquals(a, different))
    }

    @Test
    fun `nested arrays of primitive arrays compare structurally`() {
        val a = arrayOf(intArrayOf(1, 2), intArrayOf(3, 4))
        val b = arrayOf(intArrayOf(1, 2), intArrayOf(3, 4))
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        val different = arrayOf(intArrayOf(1, 2), intArrayOf(3, 5))
        assertFalse(ValueEquality.contentEquals(a, different))
        assertNotEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(different))
    }

    @Test
    fun `deeply nested multi-dimensional arrays compare structurally`() {
        val a = arrayOf(arrayOf(arrayOf(1, 2), arrayOf(3)), arrayOf(arrayOf(4)))
        val b = arrayOf(arrayOf(arrayOf(1, 2), arrayOf(3)), arrayOf(arrayOf(4)))
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))
    }

    @Test
    fun `nested object arrays holding custom value types compare by element equals`() {
        val a = arrayOf(Point(1, 2), Point(3, 4))
        val b = arrayOf(Point(1, 2), Point(3, 4))
        assertTrue(ValueEquality.contentEquals(a, b))
        assertEquals(ValueEquality.contentHashCode(a), ValueEquality.contentHashCode(b))

        assertFalse(ValueEquality.contentEquals(a, arrayOf(Point(1, 2), Point(9, 9))))
    }

    // ---- cross-kind mismatches -----------------------------------------------------------

    @Test
    fun `object array and primitive array with matching values are not equal`() {
        val boxed = arrayOf(1, 2, 3)
        val primitive = intArrayOf(1, 2, 3)
        assertFalse(ValueEquality.contentEquals(boxed, primitive))
        assertFalse(ValueEquality.contentEquals(primitive, boxed))
    }

    @Test
    fun `arrays of different primitive kinds are not equal`() {
        assertFalse(ValueEquality.contentEquals(intArrayOf(1, 2), longArrayOf(1L, 2L)))
    }

    @Test
    fun `array and non-array are not equal`() {
        assertFalse(ValueEquality.contentEquals(intArrayOf(1), "not-an-array"))
    }

    // ---- equals / hashCode consistency ---------------------------------------------------

    @Test
    fun `equal empty arrays of the same kind agree on hashCode`() {
        assertTrue(ValueEquality.contentEquals(intArrayOf(), intArrayOf()))
        assertEquals(ValueEquality.contentHashCode(intArrayOf()), ValueEquality.contentHashCode(intArrayOf()))
    }

    private fun assertContentEqualPair(
        a: Any,
        b: Any,
    ) {
        assertTrue(ValueEquality.contentEquals(a, b), "expected $a and $b to be content-equal")
        assertEquals(
            ValueEquality.contentHashCode(a),
            ValueEquality.contentHashCode(b),
            "content hashes must agree for content-equal values",
        )
    }

    private data class Point(val x: Int, val y: Int)
}
