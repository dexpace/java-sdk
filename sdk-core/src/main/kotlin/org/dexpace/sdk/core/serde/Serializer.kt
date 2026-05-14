package org.dexpace.sdk.core.serde

import java.io.InputStream
import java.io.OutputStream

/**
 * Format-agnostic serialization strategy. Implementations encode arbitrary objects to the bundle's
 * wire format (typically driven by the same [Serde] that exposes the matching [Deserializer]).
 *
 * The four overloads cover the common allocation profiles: produce a fresh `String`, produce a
 * fresh `ByteArray`, stream into a caller-owned `OutputStream`, or stream into a caller-owned
 * scratch buffer. Stream / buffer overloads do **not** close their targets — the caller retains
 * ownership.
 */
public interface Serializer {
    /** Encode [input] and return the result as a new string. */
    public fun serialize(input: Any): String

    /** Encode [input] and return the result as a freshly-allocated byte array. */
    public fun serializeToByteArray(input: Any): ByteArray

    /** Stream [input]'s encoding into [outputStream]. The caller owns closing the stream. */
    public fun serialize(input: Any, outputStream: OutputStream)

    /** Encode [input] into the caller-supplied [buffer]. Behavior on overflow is implementation-defined. */
    public fun serialize(input: Any, buffer: ByteArray)
}
