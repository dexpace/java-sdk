/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
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
 * (`List<MyDto>`, `Map<String, OtherDto>`, ...). The [Deserializer.deserialize] overloads inherited
 * from `sdk-core` carry an explicit `Class<T>` token (and a reified extension that forwards it), so
 * **non-parametric** targets materialize as their real type with no unchecked cast. For
 * **parametric** targets a raw `Class` is insufficient — use the [deserializeAs] family below
 * (which accepts a [TypeReference]).
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

        /**
         * Build a [JacksonSerde] backed by a caller-supplied [mapper].
         *
         * The caller-owned-stream contract on [Serializer.serialize] / [Deserializer.deserialize]
         * holds regardless of the mapper's `AUTO_CLOSE_TARGET`/`AUTO_CLOSE_SOURCE` settings: the
         * stream overloads drive a per-call generator/parser with auto-close disabled, so the
         * caller's [OutputStream]/[InputStream] is left open and the supplied [mapper] is never
         * mutated.
         */
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
        // The caller owns closing the stream. AUTO_CLOSE_TARGET is disabled on the per-call
        // generator instance (not the shared factory), so the caller-supplied mapper is never
        // mutated and the stream is left open after close()/flush — see JacksonSerde.from(...).
        val generator = mapper.factory.createGenerator(outputStream).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        generator.use { gen ->
            mapper.writeValue(gen, input)
        }
    }

    override fun serialize(
        input: Any,
        buffer: ByteArray,
        offset: Int,
    ): Int {
        // Render to bytes then memcpy into the caller's buffer at [offset], returning the count so
        // the caller can slice the valid prefix. Overflow / bad offset throws IndexOutOfBoundsException
        // per the Serializer contract; bounds-check is cheap.
        if (offset < 0 || offset > buffer.size) {
            throw IndexOutOfBoundsException(
                "offset $offset out of bounds for buffer of size ${buffer.size}.",
            )
        }
        val encoded = mapper.writeValueAsBytes(input)
        val remaining = buffer.size - offset
        if (encoded.size > remaining) {
            throw IndexOutOfBoundsException(
                "Encoded payload (${encoded.size} bytes) exceeds remaining buffer space " +
                    "($remaining bytes from offset $offset).",
            )
        }
        System.arraycopy(encoded, 0, buffer, offset, encoded.size)
        return encoded.size
    }
}

/**
 * Jackson-backed [Deserializer] reading from strings, byte arrays, or input streams.
 *
 * Each overload binds the target type via the caller-supplied [Class] token (`readValue(input,
 * type)`), so there is no unchecked cast and a non-parametric DTO materializes as its real type.
 * Parametric targets (`List<MyDto>`) need a [TypeReference]; use [JacksonSerde.deserializeAs].
 *
 * `internal` for the same reason as [JacksonSerializer].
 */
internal class JacksonDeserializer internal constructor(
    private val mapper: ObjectMapper,
) : Deserializer {
    override fun <T> deserialize(
        input: String,
        type: Class<T>,
    ): T = mapper.readValue(input, type)

    override fun <T> deserialize(
        input: ByteArray,
        type: Class<T>,
    ): T = mapper.readValue(input, type)

    override fun <T> deserialize(
        inputStream: InputStream,
        type: Class<T>,
    ): T {
        // The caller owns closing the stream. Drive a per-call parser with AUTO_CLOSE_SOURCE off so
        // a caller-supplied mapper (see JacksonSerde.from) is not mutated and the stream stays open.
        val parser = mapper.factory.createParser(inputStream).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
        return parser.use { p ->
            mapper.readValue(p, type)
        }
    }
}
