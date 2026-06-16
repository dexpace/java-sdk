/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.io.OutputStream

/**
 * Format-agnostic serialization strategy. Implementations encode arbitrary objects to the bundle's
 * wire format (typically driven by the same [Serde] that exposes the matching [Deserializer]).
 *
 * The four overloads cover the common allocation profiles: produce a fresh `String`, produce a
 * fresh `ByteArray`, stream into a caller-owned `OutputStream`, or stream into a caller-owned
 * scratch buffer. Stream / buffer overloads do **not** close their targets — the caller retains
 * ownership.
 *
 * Implementations surface encode failures as [SerializationException] (a [SerdeException] subtype),
 * chaining the backing codec's error as the cause, so callers catch a single stable SDK type
 * without naming the underlying library.
 */
public interface Serializer {
    /**
     * Encode [input] and return the result as a new string.
     *
     * @throws SerializationException if [input] cannot be encoded.
     */
    public fun serialize(input: Any): String

    /**
     * Encode [input] and return the result as a freshly-allocated byte array.
     *
     * @throws SerializationException if [input] cannot be encoded.
     */
    public fun serializeToByteArray(input: Any): ByteArray

    /**
     * Stream [input]'s encoding into [outputStream]. The caller owns closing the stream.
     *
     * @throws SerializationException if [input] cannot be encoded. A genuine stream-write
     *   [java.io.IOException] propagates unwrapped.
     */
    public fun serialize(
        input: Any,
        outputStream: OutputStream,
    )

    /**
     * Encode [input] into the caller-supplied [buffer] starting at [offset], returning the number
     * of bytes written. The caller can slice the valid prefix as `buffer[offset until offset + n]`.
     *
     * @throws IndexOutOfBoundsException when [offset] is negative or beyond [buffer]'s length, or
     *   when the encoded payload does not fit in the remaining space (`buffer.size - offset`). The
     *   buffer contents are unspecified after an overflow throw.
     * @throws SerializationException if [input] cannot be encoded.
     */
    public fun serialize(
        input: Any,
        buffer: ByteArray,
        offset: Int = 0,
    ): Int
}
