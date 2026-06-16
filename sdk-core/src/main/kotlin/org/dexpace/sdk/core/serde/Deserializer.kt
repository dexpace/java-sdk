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
