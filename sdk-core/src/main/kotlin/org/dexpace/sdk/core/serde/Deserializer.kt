/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.io.InputStream

/**
 * Format-agnostic deserialization strategy. Implementations decode wire bytes / strings / streams
 * into typed values, dispatching on the explicit [Class] type token the caller supplies.
 *
 * Every overload takes a [Class] witness so the implementation can recover the target type at
 * runtime — without it, the method-level `<T>` is erased and the only conformant implementation is
 * an unchecked cast to `Any`, which silently returns a `LinkedHashMap`/`List` and throws
 * `ClassCastException` at the first field access (heap pollution). The reified
 * [deserialize] extension forwards `T::class.java` for the common "the caller already knows `T` at
 * compile time" case.
 *
 * For parametric targets (`List<MyDto>`, `Map<String, MyDto>`) the raw [Class] token is
 * insufficient; adapter modules expose their own type-reference entry points (e.g.
 * `sdk-serde-jackson`'s `JacksonSerde.deserializeAs`).
 *
 * Implementations surface decode failures as [DeserializationException] (a [SerdeException]
 * subtype), chaining the backing codec's error as the cause, so callers catch a single stable SDK
 * type without naming the underlying library.
 */
public interface Deserializer {
    /**
     * Decode a complete document of [type] from the in-memory [input] string.
     *
     * @throws DeserializationException if [input] is malformed or does not match [type].
     */
    public fun <T> deserialize(
        input: String,
        type: Class<T>,
    ): T

    /**
     * Decode a complete document of [type] from the in-memory [input] byte array.
     *
     * @throws DeserializationException if [input] is malformed or does not match [type].
     */
    public fun <T> deserialize(
        input: ByteArray,
        type: Class<T>,
    ): T

    /**
     * Decode a complete document of [type] by streaming from [inputStream]. The implementation owns
     * reading to EOF but **does not** close the stream — the caller retains ownership.
     *
     * @throws DeserializationException if the payload is malformed or does not match [type]. A
     *   genuine stream-read [java.io.IOException] propagates unwrapped.
     */
    public fun <T> deserialize(
        inputStream: InputStream,
        type: Class<T>,
    ): T

    /**
     * Decode a complete document of the parametric type captured by [ref] from the in-memory [input]
     * string. This is the entry point for generic targets (`List<MyDto>`, `Map<String, MyDto>`) whose
     * element types a raw [Class] token would erase.
     *
     * The default implementation only supports a [ref] whose [TypeRef.type] is a plain [Class] (no
     * type arguments), delegating to the [Class] overload; for a genuinely parametric [ref] it throws
     * [SerdeException], because a format-agnostic [Deserializer] cannot resolve type arguments without
     * codec support. Implementations backed by a codec that resolves generics (e.g. Jackson) override
     * this to decode parametric targets.
     *
     * @throws SerdeException if this deserializer cannot resolve the generic [ref], or (from an
     *   overriding implementation) [DeserializationException] if [input] is malformed or mismatched.
     */
    public fun <T> deserialize(
        input: String,
        ref: TypeRef<T>,
    ): T = deserializeViaRawClass(ref) { rawType -> deserialize(input, rawType) }

    /**
     * Decode a complete document of the parametric type captured by [ref] by streaming from
     * [inputStream]. The implementation owns reading to EOF but **does not** close the stream — the
     * caller retains ownership. See the string overload for the generic-resolution contract.
     *
     * @throws SerdeException if this deserializer cannot resolve the generic [ref], or (from an
     *   overriding implementation) [DeserializationException] if the payload is malformed or
     *   mismatched.
     */
    public fun <T> deserialize(
        inputStream: InputStream,
        ref: TypeRef<T>,
    ): T = deserializeViaRawClass(ref) { rawType -> deserialize(inputStream, rawType) }
}

/**
 * Shared default-path helper for the [Deserializer] `TypeRef` overloads: if [ref] wraps a plain
 * [Class] (no type arguments) invoke [decodeAsClass] with that raw class; otherwise fail, since a
 * format-agnostic [Deserializer] has no way to resolve generic type arguments.
 */
@Suppress("UNCHECKED_CAST")
private inline fun <T> deserializeViaRawClass(
    ref: TypeRef<T>,
    decodeAsClass: (Class<T>) -> T,
): T {
    val captured = ref.type
    if (captured is Class<*>) {
        return decodeAsClass(captured as Class<T>)
    }
    throw SerdeException(
        "This Deserializer does not resolve generic types (${captured.typeName}); use a serde that " +
            "overrides the TypeRef overloads to decode parametric targets (e.g. JacksonSerde).",
    )
}

/** Decode a complete document from the in-memory [input] string into the reified type [T]. */
public inline fun <reified T> Deserializer.deserialize(input: String): T = deserialize(input, T::class.java)

/** Decode a complete document from the in-memory [input] byte array into the reified type [T]. */
public inline fun <reified T> Deserializer.deserialize(input: ByteArray): T = deserialize(input, T::class.java)

/**
 * Decode a complete document by streaming from [inputStream] into the reified type [T]. The stream
 * is read to EOF but **not** closed — the caller retains ownership.
 */
public inline fun <reified T> Deserializer.deserialize(inputStream: InputStream): T =
    deserialize(inputStream, T::class.java)
