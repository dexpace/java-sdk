package org.dexpace.sdk.core.serde

import java.io.InputStream

/**
 * Format-agnostic deserialization strategy. Implementations decode wire bytes / strings / streams
 * into typed values, dispatching on the request payload's [T] (typically a generated POJO or a
 * generic container).
 *
 * The `<T>` parameter is *method-level*, so implementations must recover the target type at the call
 * site — typically via a reified caller wrapper or by accepting a `TypeReference` / `KClass` in a
 * downstream overload. The bare overloads here exist for the common "the caller already knows T at
 * compile time" case.
 */
public interface Deserializer {
    /** Decode a complete document from the in-memory [input] string. */
    public fun <T> deserialize(input: String): T

    /** Decode a complete document from the in-memory [input] byte array. */
    public fun <T> deserialize(input: ByteArray): T

    /**
     * Decode a complete document by streaming from [inputStream]. The implementation owns reading
     * to EOF but **does not** close the stream — the caller retains ownership.
     */
    public fun <T> deserialize(inputStream: InputStream): T
}
