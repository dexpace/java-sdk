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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TypeRefTest {
    data class User(val id: Int)

    // Invariant container: its reflected type argument is the element type itself, unlike the
    // read-only List<out E>, which Kotlin reflects as java.util.List<? extends E> (a wildcard).
    class Box<T>(
        @Suppress("unused") val value: T,
    )

    // A generic subclass of TypeRef. Its genericSuperclass is `TypeRef<T>` whose actual type
    // argument is the *unresolved* type variable T — erased to Any at construction. Used to prove
    // the constructor rejects a type it cannot faithfully capture.
    private class Foo<T> : TypeRef<T>()

    // Anonymous TypeRef built inside a generic function: T is the function's type variable, which
    // is likewise erased, so construction must fail.
    private fun <T> makeErasedRef(): TypeRef<T> = object : TypeRef<T>() {}

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

    @Test
    fun `anonymous TypeRef built in a generic function rejects the unresolved type variable`() {
        // T is the function's type variable — erased to its bound at construction, so capturing it
        // would silently decode into the wrong type. The constructor must reject it up front.
        assertFailsWith<IllegalArgumentException> { makeErasedRef<User>() }
    }

    @Test
    fun `generic subclass of TypeRef rejects its unresolved type variable`() {
        // `class Foo<T> : TypeRef<T>()` used as Foo<User>() records `TypeRef<T>` (a type variable),
        // not `TypeRef<User>`, as its genericSuperclass — the User argument is erased. Rejected.
        assertFailsWith<IllegalArgumentException> { Foo<User>() }
    }

    @Test
    fun `concrete parametric ref built at the call site still succeeds`() {
        // Control for the two rejection cases above: a concrete argument supplied at the call site
        // is faithfully captured, so construction succeeds and the full generic type survives.
        val ref = object : TypeRef<List<User>>() {}
        val type = ref.type
        assertTrue(type is ParameterizedType)
        assertSame(List::class.java, type.rawType)
        assertSame(List::class.java, ref.rawClass)
        // Kotlin's `List<out E>` variance means the captured argument is `? extends User`, not the
        // bare `User` — the point here is only that a concrete parametric type is faithfully
        // captured (contrast the rejected type-variable cases above).
        assertEquals(1, type.actualTypeArguments.size)
    }
}
