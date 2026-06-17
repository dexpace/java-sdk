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
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenUnionTest {
    /**
     * Hand-written concrete union exercising the [OpenUnion] base exactly as a generated type
     * would: a private constructor, one nullable slot + typed accessor per arm, a visitor extending
     * [OpenUnion.Visitor], and an `ofUnknown` factory that retains the raw node.
     */
    private class StringOrNumber private constructor(
        private val string: String?,
        private val number: Long?,
        rawValue: Any?,
    ) : OpenUnion<StringOrNumber.Visitor<*>>(rawValue) {
        fun asString(): String? = string

        fun asNumber(): Long? = number

        interface Visitor<out R> : OpenUnion.Visitor<R> {
            fun visitString(value: String): R

            fun visitNumber(value: Long): R
        }

        override val arms: List<Arm<Visitor<*>>> =
            listOf(
                Arm(string) { v, value -> v.visitString(value as String) },
                Arm(number) { v, value -> v.visitNumber(value as Long) },
            )

        companion object {
            fun ofString(value: String): StringOrNumber = StringOrNumber(value, null, value)

            fun ofNumber(value: Long): StringOrNumber = StringOrNumber(null, value, value)

            fun ofUnknown(rawValue: Any?): StringOrNumber = StringOrNumber(null, null, rawValue)
        }
    }

    private class Renderer : StringOrNumber.Visitor<String> {
        override fun visitString(value: String): String = "string:$value"

        override fun visitNumber(value: Long): String = "number:$value"
    }

    @Test
    fun `accept dispatches to the string case for a string variant`() {
        val union = StringOrNumber.ofString("hello")

        assertEquals("hello", union.asString())
        assertNull(union.asNumber())
        assertEquals("string:hello", union.accept(Renderer()))
    }

    @Test
    fun `accept dispatches to the number case for a number variant`() {
        val union = StringOrNumber.ofNumber(42L)

        assertEquals(42L, union.asNumber())
        assertNull(union.asString())
        assertEquals("number:42", union.accept(Renderer()))
    }

    @Test
    fun `unknown variant retains its raw node and is not lost`() {
        val raw = mapOf("type" to "future", "payload" to 7)
        val union = StringOrNumber.ofUnknown(raw)

        assertTrue(union.isUnknown)
        assertNull(union.asString())
        assertNull(union.asNumber())
        // Raw node preserved so a serializer can re-emit the unrecognised shape verbatim.
        assertEquals(raw, union.rawValue)
    }

    @Test
    fun `accept routes an unknown variant to the default unknown() which throws`() {
        val union = StringOrNumber.ofUnknown("mystery")

        try {
            union.accept(Renderer())
            fail("expected IllegalStateException from default unknown()")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mystery"))
        }
    }

    @Test
    fun `a visitor overriding unknown() handles the forward-compat path without throwing`() {
        val raw = listOf(1, 2, 3)
        val union = StringOrNumber.ofUnknown(raw)

        val tolerant =
            object : StringOrNumber.Visitor<String> {
                override fun visitString(value: String): String = "string:$value"

                override fun visitNumber(value: Long): String = "number:$value"

                override fun unknown(rawValue: Any?): String = "unknown:$rawValue"
            }

        assertEquals("unknown:[1, 2, 3]", union.accept(tolerant))
    }

    @Test
    fun `populated variants report isUnknown false`() {
        assertFalse(StringOrNumber.ofString("x").isUnknown)
        assertFalse(StringOrNumber.ofNumber(1L).isUnknown)
    }
}
