/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.lang.reflect.Array as ReflectArray

/**
 * Captures a full generic [Type] at compile time so a [Deserializer] can materialize parametric
 * targets (`List<User>`, `Map<String, User>`) that a bare [Class] token would erase.
 *
 * Usage — always as an anonymous subclass, so the type argument is reified into the class's
 * generic superclass:
 *
 * ```
 * val users: List<User> = deserializer.deserialize(json, object : TypeRef<List<User>>() {})
 * ```
 *
 * This is the [Gson](https://github.com/google/gson) `TypeToken` / Jackson `TypeReference` pattern:
 * an empty anonymous subclass records `TypeRef<List<User>>` as its `genericSuperclass`, and the
 * constructor reads the `List<User>` type argument back out via reflection. `sdk-core` stays
 * dependency-free — this uses only `java.lang.reflect`, no serde library types leak into core.
 *
 * `sdk-core`'s default [Deserializer.deserialize] can honour a `TypeRef` only when it wraps a plain
 * [Class] (no type arguments); a serde that resolves generics (e.g. `JacksonSerde`) overrides the
 * `TypeRef` overloads to decode parametric targets.
 *
 * @param T The type captured by this reference.
 */
public abstract class TypeRef<T> protected constructor() {
    /**
     * The captured generic [Type]. For `object : TypeRef<List<User>>() {}` this is the
     * [ParameterizedType] `List<User>`; for `object : TypeRef<User>() {}` it is the [Class] `User`.
     */
    public val type: Type

    /**
     * The erased raw [Class] of [type] — `List` for `List<User>`, `User` for `User`. Useful for
     * `instanceof`-style checks and for a [Deserializer] to detect the non-parametric fast path.
     */
    public val rawClass: Class<*>

    init {
        val superClass = javaClass.genericSuperclass
        require(superClass is ParameterizedType) {
            "TypeRef must be created as an anonymous subclass with a type argument, " +
                "e.g. object : TypeRef<List<User>>() {}."
        }
        type = superClass.actualTypeArguments.first()
        rawClass = rawClassOf(type)
    }

    private companion object {
        /** Resolves the erased raw [Class] backing any reflective [Type]. */
        private fun rawClassOf(type: Type): Class<*> =
            when (type) {
                is Class<*> -> type
                is ParameterizedType -> type.rawType as Class<*>
                is GenericArrayType ->
                    ReflectArray.newInstance(rawClassOf(type.genericComponentType), 0).javaClass
                is WildcardType -> rawClassOf(type.upperBounds.first())
                is TypeVariable<*> -> rawClassOf(type.bounds.first())
                else -> Any::class.java
            }
    }
}
