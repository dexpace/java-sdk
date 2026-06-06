/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.Serializer
import java.io.InputStream
import java.io.OutputStream

/**
 * Jackson-backed implementation of [Serde]. Bundles a [Serializer] and [Deserializer] that
 * delegate to a single [ObjectMapper], so the SDK's request and response pipelines can pull
 * one injection point and round-trip values without library types leaking past the seam.
 *
 * The mapper itself is constructed via [JacksonObjectMappers.defaultObjectMapper] (which sets
 * the SDK-correct defaults — see that file's KDoc) when callers use [withDefaults]; consumers
 * with bespoke serialization needs can pass their own pre-configured [ObjectMapper] instead.
 *
 * ## Type erasure and `T`
 *
 * Jackson's `readValue` requires runtime type information to materialize parametric types
 * (`List<MyDto>`, `Map<String, OtherDto>`, ...). The bare [Deserializer.deserialize] overloads
 * inherited from `sdk-core` use a method-level type parameter that is erased at runtime and
 * therefore cannot disambiguate parametric targets. Two pragmatic options:
 *
 *  - For **non-parametric** targets, the inherited overloads work because Jackson is told the
 *    raw class at the call site via the inferred reified type. In practice, prefer the
 *    [deserializeAs] family below for any non-trivial type.
 *  - For **parametric** targets, use the [deserializeAs] family (which accepts a
 *    [TypeReference]) or the reified extension at the call site.
 *
 * ## Thread-safety
 *
 * [ObjectMapper] is documented as thread-safe **after configuration is complete**. Callers that
 * follow the recommended pattern (build mapper once at startup, do not mutate afterwards) can
 * share a single [JacksonSerde] across threads safely.
 */
public class JacksonSerde private constructor(
    /** The underlying Jackson mapper. Exposed for advanced wiring (e.g. registering extra modules). */
    public val mapper: ObjectMapper,
) : Serde {
    override val serializer: Serializer = JacksonSerializer(mapper)
    override val deserializer: Deserializer = JacksonDeserializer(mapper)

    /**
     * Deserialize [input] into a value of the type captured by [type]. This overload is the
     * "correct" entry point for parametric or otherwise erased generic targets.
     */
    public fun <T> deserializeAs(
        input: String,
        type: TypeReference<T>,
    ): T = mapper.readValue(input, type)

    /** Deserialize from a [ByteArray]. See [deserializeAs] for type-reference rationale. */
    public fun <T> deserializeAs(
        input: ByteArray,
        type: TypeReference<T>,
    ): T = mapper.readValue(input, type)

    /**
     * Deserialize by streaming from [inputStream]. The implementation reads to EOF but does
     * **not** close the stream — the caller retains ownership.
     */
    public fun <T> deserializeAs(
        inputStream: InputStream,
        type: TypeReference<T>,
    ): T = mapper.readValue(inputStream, type)

    public companion object {
        /** Build a [JacksonSerde] backed by an [ObjectMapper] with the SDK-correct defaults. */
        @JvmStatic
        public fun withDefaults(): JacksonSerde = JacksonSerde(JacksonObjectMappers.defaultObjectMapper())

        /** Build a [JacksonSerde] backed by a caller-supplied [mapper]. */
        @JvmStatic
        public fun from(mapper: ObjectMapper): JacksonSerde = JacksonSerde(mapper)
    }
}

/**
 * Jackson-backed [Serializer] writing to strings, byte arrays, or output streams.
 *
 * `internal` — not meant for direct consumption; clients pull the [Serde] facade. Strict-mode
 * Kotlin requires explicit visibility on every declaration, hence the marker.
 */
internal class JacksonSerializer internal constructor(
    private val mapper: ObjectMapper,
) : Serializer {
    override fun serialize(input: Any): String = mapper.writeValueAsString(input)

    override fun serializeToByteArray(input: Any): ByteArray = mapper.writeValueAsBytes(input)

    override fun serialize(
        input: Any,
        outputStream: OutputStream,
    ) {
        // Jackson's writeValue(OutputStream, Object) does NOT close the stream; caller owns it.
        mapper.writeValue(outputStream, input)
    }

    override fun serialize(
        input: Any,
        buffer: ByteArray,
    ) {
        // Render to bytes then memcpy into the caller's buffer. Behavior on overflow: throw a
        // standard IndexOutOfBoundsException — documented as "implementation-defined" on the
        // Serializer contract, and bounds-check is cheap.
        val encoded = mapper.writeValueAsBytes(input)
        if (encoded.size > buffer.size) {
            throw IndexOutOfBoundsException(
                "Encoded payload (${encoded.size} bytes) exceeds caller buffer (${buffer.size} bytes).",
            )
        }
        System.arraycopy(encoded, 0, buffer, 0, encoded.size)
    }
}

/**
 * Jackson-backed [Deserializer] reading from strings, byte arrays, or input streams.
 *
 * The method-level `<T>` is erased at runtime; this implementation uses `Object::class.java`
 * as the deserialization target by default. Callers needing precise typing should use
 * [JacksonSerde.deserializeAs] which accepts a [TypeReference].
 *
 * `internal` for the same reason as [JacksonSerializer].
 */
internal class JacksonDeserializer internal constructor(
    private val mapper: ObjectMapper,
) : Deserializer {
    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(input: String): T = mapper.readValue(input, Any::class.java) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(input: ByteArray): T = mapper.readValue(input, Any::class.java) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(inputStream: InputStream): T = mapper.readValue(inputStream, Any::class.java) as T
}
