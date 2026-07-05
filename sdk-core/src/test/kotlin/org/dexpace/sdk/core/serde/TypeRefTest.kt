/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TypeRefTest {
    data class User(val id: Int)

    // Invariant container: its reflected type argument is the element type itself, unlike the
    // read-only List<out E>, which Kotlin reflects as java.util.List<? extends E> (a wildcard).
    class Box<T>(
        @Suppress("unused") val value: T,
    )

    @Test
    fun `non-generic ref captures the plain Class as both type and rawClass`() {
        val ref = object : TypeRef<User>() {}
        assertSame(User::class.java, ref.type)
        assertSame(User::class.java, ref.rawClass)
    }

    @Test
    fun `parametric ref captures a ParameterizedType with the erased raw class`() {
        val ref = object : TypeRef<List<User>>() {}
        val type = ref.type
        assertTrue(type is ParameterizedType, "List<User> must be captured as a ParameterizedType")
        assertSame(List::class.java, type.rawType)
        // rawClass erases the type arguments down to the container class.
        assertSame(List::class.java, ref.rawClass)
    }

    @Test
    fun `invariant parametric ref captures the exact element type`() {
        val ref = object : TypeRef<Box<User>>() {}
        val type = ref.type
        assertTrue(type is ParameterizedType, "Box<User> must be captured as a ParameterizedType")
        assertSame(Box::class.java, type.rawType)
        assertSame(User::class.java, type.actualTypeArguments.first())
        assertSame(Box::class.java, ref.rawClass)
    }

    @Test
    fun `nested parametric ref preserves the full type while erasing rawClass to the container`() {
        val ref = object : TypeRef<Map<String, List<User>>>() {}
        val type = ref.type
        assertTrue(type is ParameterizedType, "Map<String, List<User>> must be a ParameterizedType")
        assertSame(Map::class.java, type.rawType)
        assertSame(Map::class.java, ref.rawClass)
        assertEquals(2, type.actualTypeArguments.size)
    }
}
