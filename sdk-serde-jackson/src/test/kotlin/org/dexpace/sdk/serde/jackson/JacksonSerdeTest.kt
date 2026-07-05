/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.serde.DeserializationException
import org.dexpace.sdk.core.serde.SerdeException
import org.dexpace.sdk.core.serde.SerializationException
import org.dexpace.sdk.core.serde.Tristate
import org.dexpace.sdk.core.serde.TypeRef
import org.dexpace.sdk.core.serde.deserialize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JacksonSerdeTest {
    // Kotlin data class with a nested object, list, java.time.Instant, Optional<T>, Tristate<T>.
    data class Inner(val tag: String, val score: Int)

    // Minimal DTO with a Tristate field; used to verify PATCH-field serialization correctness.
    data class PatchDto(
        val name: String = "",
        val description: Tristate<String> = Tristate.Absent,
    )

    data class FullModel(
        val name: String,
        val children: List<Inner>,
        val createdAt: Instant,
        val nickname: Optional<String>,
        val description: Tristate<String> = Tristate.Absent,
    )

    @Test
    fun `round-trip Kotlin data class with nested object, list, Instant, optional, Tristate`() {
        val serde = JacksonSerde.withDefaults()
        val original =
            FullModel(
                name = "root",
                children = listOf(Inner("a", 1), Inner("b", 2)),
                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                nickname = Optional.of("buddy"),
                description = Tristate.Present("readable"),
            )
        val asString = serde.serializer.serialize(original)
        val parsed: FullModel = serde.deserializeAs(asString, object : TypeReference<FullModel>() {})
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize to byte array and round-trip`() {
        val serde = JacksonSerde.withDefaults()
        val item = Inner("x", 42)
        val bytes = serde.serializer.serializeToByteArray(item)
        val back: Inner = serde.deserializeAs(bytes, object : TypeReference<Inner>() {})
        assertEquals(item, back)
    }

    @Test
    fun `serialize to OutputStream does not close the stream`() {
        val serde = JacksonSerde.withDefaults()
        val baos = ByteArrayOutputStream()
        val tracker = TrackingOutputStream(baos)
        serde.serializer.serialize(Inner("y", 7), tracker)
        assertTrue(tracker.bytesWritten > 0)
        assertEquals(0, tracker.closeCount, "Serializer must not close caller-owned OutputStream")
    }

    @Test
    fun `serialize to ByteArray buffer returns written length and fills the prefix`() {
        val serde = JacksonSerde.withDefaults()
        val payload = Inner("z", 9)
        val expected = serde.serializer.serializeToByteArray(payload)
        val buffer = ByteArray(expected.size + 16)
        val written = serde.serializer.serialize(payload, buffer)
        // The overload now reports the byte count so the caller can slice the valid prefix.
        assertEquals(expected.size, written, "serialize(buffer) must return the bytes written")
        val prefix = buffer.copyOfRange(0, written)
        assertTrue(prefix.contentEquals(expected))
    }

    @Test
    fun `serialize to ByteArray buffer honours a non-zero offset`() {
        val serde = JacksonSerde.withDefaults()
        val payload = Inner("z", 9)
        val expected = serde.serializer.serializeToByteArray(payload)
        val offset = 8
        val buffer = ByteArray(offset + expected.size + 4)
        val written = serde.serializer.serialize(payload, buffer, offset)
        assertEquals(expected.size, written)
        val region = buffer.copyOfRange(offset, offset + written)
        assertTrue(region.contentEquals(expected), "encoding must land at the requested offset")
        // Bytes before the offset are untouched (still zero).
        assertTrue(buffer.copyOfRange(0, offset).all { it == 0.toByte() })
    }

    @Test
    fun `serialize to ByteArray buffer throws when payload overflows remaining space`() {
        val serde = JacksonSerde.withDefaults()
        val payload = Inner("overflow-me", 1234)
        val expected = serde.serializer.serializeToByteArray(payload)
        // One byte short of what is needed.
        val buffer = ByteArray(expected.size - 1)
        assertFailsWith<IndexOutOfBoundsException> {
            serde.serializer.serialize(payload, buffer)
        }
    }

    @Test
    fun `serialize to ByteArray buffer throws on out-of-range offset`() {
        val serde = JacksonSerde.withDefaults()
        val buffer = ByteArray(64)
        assertFailsWith<IndexOutOfBoundsException> {
            serde.serializer.serialize(Inner("a", 1), buffer, -1)
        }
        assertFailsWith<IndexOutOfBoundsException> {
            serde.serializer.serialize(Inner("a", 1), buffer, buffer.size + 1)
        }
    }

    @Test
    fun `deserialize with Class token returns the concrete DTO type, not a map`() {
        val serde = JacksonSerde.withDefaults()
        val json = """{"tag":"t","score":99}"""
        // Class<T> token path: no unchecked cast, real type materialized.
        val dto: Inner = serde.deserializer.deserialize(json, Inner::class.java)
        assertEquals(Inner("t", 99), dto)
        // Field access on the typed result must not throw ClassCastException (heap pollution guard).
        assertEquals("t", dto.tag)
        assertEquals(99, dto.score)
    }

    @Test
    fun `deserializer decodes a parametric TypeRef from a JSON array without a Jackson type at the call site`() {
        val serde = JacksonSerde.withDefaults()
        val json = """[{"tag":"a","score":1},{"tag":"b","score":2}]"""
        // The decode call names only org.dexpace.sdk.core.serde.TypeRef — no com.fasterxml.jackson
        // type is required to resolve List<Inner>. This is the dep-free parametric-decode contract.
        val list: List<Inner> = serde.deserializer.deserialize(json, object : TypeRef<List<Inner>>() {})
        assertEquals(listOf(Inner("a", 1), Inner("b", 2)), list)
    }

    @Test
    fun `deserializer decodes a parametric TypeRef from an InputStream`() {
        val serde = JacksonSerde.withDefaults()
        val json = """[{"tag":"x","score":9}]"""
        val list: List<Inner> =
            serde.deserializer.deserialize(ByteArrayInputStream(json.toByteArray()), object : TypeRef<List<Inner>>() {})
        assertEquals(listOf(Inner("x", 9)), list)
    }

    @Test
    fun `deserializer decodes a non-generic TypeRef via the raw-Class path`() {
        val serde = JacksonSerde.withDefaults()
        val dto: Inner = serde.deserializer.deserialize("""{"tag":"solo","score":3}""", object : TypeRef<Inner>() {})
        assertEquals(Inner("solo", 3), dto)
    }

    @Test
    fun `reified deserialize extension forwards the type token`() {
        val serde = JacksonSerde.withDefaults()
        val json = """{"tag":"r","score":7}"""
        // The ergonomic reified extension forwards T::class.java to the SPI.
        val dto = serde.deserializer.deserialize<Inner>(json)
        assertEquals(Inner("r", 7), dto)
    }

    @Test
    fun `deserialize with Class token from byte array and input stream`() {
        val serde = JacksonSerde.withDefaults()
        val json = """{"tag":"b","score":3}"""
        val fromBytes: Inner = serde.deserializer.deserialize(json.toByteArray(), Inner::class.java)
        assertEquals(Inner("b", 3), fromBytes)
        val fromStream: Inner =
            serde.deserializer.deserialize(java.io.ByteArrayInputStream(json.toByteArray()), Inner::class.java)
        assertEquals(Inner("b", 3), fromStream)
    }

    @Test
    fun `from plain mapper leaves the caller OutputStream open on serialize`() {
        // A mapper with AUTO_CLOSE_TARGET still enabled (the default). The from() path must not
        // close the caller's stream regardless.
        val serde = JacksonSerde.from(ObjectMapper().registerKotlinModule())
        val tracker = TrackingOutputStream(ByteArrayOutputStream())
        serde.serializer.serialize(Inner("open", 1), tracker)
        assertTrue(tracker.bytesWritten > 0)
        assertEquals(0, tracker.closeCount, "from(plain mapper) must not close the caller OutputStream")
    }

    @Test
    fun `from plain mapper leaves the caller InputStream open on deserialize`() {
        val serde = JacksonSerde.from(ObjectMapper().registerKotlinModule())
        val json = """{"tag":"s","score":5}"""
        val tracker = TrackingInputStream(java.io.ByteArrayInputStream(json.toByteArray()))
        val dto: Inner = serde.deserializer.deserialize(tracker, Inner::class.java)
        assertEquals(Inner("s", 5), dto)
        assertEquals(0, tracker.closeCount, "from(plain mapper) must not close the caller InputStream")
    }

    // ----- T10: from(mapper) must register TristateModule (silent-corruption fix) -----

    @Test
    fun `from bare ObjectMapper round-trips Tristate present`() {
        val serde = JacksonSerde.from(ObjectMapper().registerKotlinModule())
        val dto = PatchDto(name = "x", description = Tristate.Present("patch-value"))
        val json = serde.serializer.serialize(dto)
        assertTrue(json.contains("\"description\""), "Present field must appear in JSON: $json")
        assertTrue(json.contains("\"patch-value\""), "Present value must appear in JSON: $json")
    }

    @Test
    fun `from bare ObjectMapper serializes Tristate absent by omitting the field`() {
        val serde = JacksonSerde.from(ObjectMapper().registerKotlinModule())
        val dto = PatchDto(name = "y", description = Tristate.Absent)
        val json = serde.serializer.serialize(dto)
        assertFalse(json.contains("\"description\""), "Absent field must be omitted from JSON: $json")
    }

    @Test
    fun `from bare ObjectMapper serializes Tristate null as JSON null`() {
        val serde = JacksonSerde.from(ObjectMapper().registerKotlinModule())
        val dto = PatchDto(name = "z", description = Tristate.Null)
        val json = serde.serializer.serialize(dto)
        assertTrue(json.contains("\"description\""), "Null field key must appear in JSON: $json")
        assertTrue(json.contains(":null"), "Null field value must be JSON null: $json")
    }

    @Test
    fun `from does not mutate the caller's original mapper`() {
        val original = ObjectMapper().registerKotlinModule()
        JacksonSerde.from(original)
        // If from() mutated original (added TristateModule), Tristate.Absent would be omitted.
        // Without TristateModule, ObjectMapper treats Tristate.Absent as a regular object and
        // includes the field in the output.
        val dto = PatchDto(name = "check", description = Tristate.Absent)
        val json = original.writeValueAsString(dto)
        assertTrue(json.contains("\"description\""), "Original mapper must not be mutated by from(): $json")
    }

    @Test
    fun `from with registerTristate false skips auto-registration`() {
        // Callers who already registered TristateModule manually should opt out to avoid
        // a double registration. The copy of their mapper already has the module.
        val pre = ObjectMapper().registerKotlinModule().also { it.registerModule(TristateModule()) }
        val serde = JacksonSerde.from(pre, registerTristate = false)
        // Serde is functional because TristateModule was pre-registered by the caller.
        val dto = PatchDto(name = "a", description = Tristate.Present("v"))
        val json = serde.serializer.serialize(dto)
        assertTrue(json.contains("\"description\""))
        assertTrue(json.contains("\"v\""))
    }

    // ----- T8-jackson: jsonBody convenience -----

    @Test
    fun `jsonBody produces application-json body with expected content length and media type`() {
        val serde = JacksonSerde.withDefaults()
        val dto = Inner("hello", 42)
        val body = jsonBody(serde, dto)
        val expectedBytes = serde.serializer.serializeToByteArray(dto)
        assertEquals(MediaType.parse("application/json"), body.mediaType())
        assertEquals(expectedBytes.size.toLong(), body.contentLength())
        assertTrue(body.isReplayable())
    }

    // Java-style builder-pattern class. Jackson's @JsonDeserialize(builder = ...) + @JsonPOJOBuilder
    // is the canonical builder-friendly immutability pattern called out in the spec.
    @JsonDeserialize(builder = BuilderModel.Builder::class)
    class BuilderModel private constructor(
        val title: String,
        val priority: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is BuilderModel && other.title == title && other.priority == priority

        override fun hashCode(): Int = title.hashCode() * 31 + priority

        @JsonPOJOBuilder(withPrefix = "set")
        class Builder {
            private var title: String = ""
            private var priority: Int = 0

            fun setTitle(
                @JsonProperty("title") value: String,
            ): Builder {
                this.title = value
                return this
            }

            fun setPriority(
                @JsonProperty("priority") value: Int,
            ): Builder {
                this.priority = value
                return this
            }

            fun build(): BuilderModel = BuilderModel(title, priority)
        }
    }

    @Test
    fun `builder-style Java class with JsonDeserialize builder deserializes correctly`() {
        val serde = JacksonSerde.withDefaults()
        val json = """{"title":"task","priority":5}"""
        val parsed: BuilderModel = serde.deserializeAs(json, object : TypeReference<BuilderModel>() {})
        assertNotNull(parsed)
        assertEquals("task", parsed.title)
        assertEquals(5, parsed.priority)
    }

    @Test
    fun `JacksonSerde from caller-supplied mapper produces a working serde backed by a copy`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        val serde = JacksonSerde.from(mapper)
        // from() works on a defensive copy — never the same reference as the caller's mapper.
        assertFalse(serde.mapper === mapper, "from() must back the serde with a copy, not the original mapper")
        // The serde is fully functional via the copy.
        val dto = Inner("check", 1)
        val json = serde.serializer.serialize(dto)
        val back: Inner = serde.deserializer.deserialize(json, Inner::class.java)
        assertEquals(dto, back)
    }

    // ----- SPI error wrapping (#22): Jackson exception types must not leak across the Serde SPI -----

    // An object the mapper cannot serialize (self-referential cycle) forces a write-side failure
    // without depending on any field annotation.
    class SelfReferential {
        @Suppress("unused")
        val self: SelfReferential get() = this
    }

    @Test
    fun `deserialize from malformed JSON string surfaces SerdeException not a Jackson type`() {
        val serde = JacksonSerde.withDefaults()
        val ex =
            assertFailsWith<DeserializationException> {
                serde.deserializer.deserialize("""{"tag": """, Inner::class.java)
            }
        assertTrue(
            SerdeException::class.java.isInstance(ex),
            "DeserializationException must be a SerdeException",
        )
        assertFalse(
            JsonProcessingException::class.java.isInstance(ex),
            "SPI boundary must not leak com.fasterxml.* exception types",
        )
        // The originating Jackson failure is preserved as the cause for diagnostics.
        assertNotNull(ex.cause)
        assertTrue(
            JsonProcessingException::class.java.isInstance(ex.cause),
            "original Jackson cause must be chained",
        )
    }

    @Test
    fun `deserialize from malformed JSON byte array surfaces SerdeException`() {
        val serde = JacksonSerde.withDefaults()
        assertFailsWith<DeserializationException> {
            serde.deserializer.deserialize("""not json at all""".toByteArray(), Inner::class.java)
        }
    }

    @Test
    fun `deserialize from malformed JSON input stream surfaces SerdeException`() {
        val serde = JacksonSerde.withDefaults()
        assertFailsWith<DeserializationException> {
            serde.deserializer.deserialize(
                ByteArrayInputStream("""{"tag": 1, """.toByteArray()),
                Inner::class.java,
            )
        }
    }

    @Test
    fun `deserializeAs with malformed JSON surfaces SerdeException across all overloads`() {
        val serde = JacksonSerde.withDefaults()
        val ref = object : TypeReference<Inner>() {}
        assertFailsWith<DeserializationException> { serde.deserializeAs("""{bad""", ref) }
        assertFailsWith<DeserializationException> { serde.deserializeAs("""{bad""".toByteArray(), ref) }
        assertFailsWith<DeserializationException> {
            serde.deserializeAs(ByteArrayInputStream("""{bad""".toByteArray()), ref)
        }
    }

    @Test
    fun `type-mismatch payload surfaces SerdeException`() {
        val serde = JacksonSerde.withDefaults()
        // score is an Int field; an object value is a hard mismatch Jackson raises as
        // MismatchedInputException (a JsonProcessingException subtype).
        val ex =
            assertFailsWith<DeserializationException> {
                serde.deserializer.deserialize("""{"tag":"t","score":{"nested":1}}""", Inner::class.java)
            }
        assertTrue(JsonProcessingException::class.java.isInstance(ex.cause))
    }

    @Test
    fun `serialize of an unserializable value surfaces SerializationException`() {
        val serde = JacksonSerde.withDefaults()
        val ex =
            assertFailsWith<SerializationException> {
                serde.serializer.serialize(SelfReferential())
            }
        assertTrue(
            SerdeException::class.java.isInstance(ex),
            "SerializationException must be a SerdeException",
        )
        assertFalse(
            JsonProcessingException::class.java.isInstance(ex),
            "SPI boundary must not leak Jackson types",
        )
        assertNotNull(ex.cause)
    }

    @Test
    fun `serialize-to-byte-array of an unserializable value surfaces SerializationException`() {
        val serde = JacksonSerde.withDefaults()
        assertFailsWith<SerializationException> {
            serde.serializer.serializeToByteArray(SelfReferential())
        }
    }

    @Test
    fun `serialize-to-stream of an unserializable value surfaces SerializationException`() {
        val serde = JacksonSerde.withDefaults()
        assertFailsWith<SerializationException> {
            serde.serializer.serialize(SelfReferential(), ByteArrayOutputStream())
        }
    }

    @Test
    fun `serialize-to-buffer of an unserializable value surfaces SerializationException`() {
        val serde = JacksonSerde.withDefaults()
        assertFailsWith<SerializationException> {
            serde.serializer.serialize(SelfReferential(), ByteArray(4096))
        }
    }

    @Test
    fun `serialize-to-buffer preserves IndexOutOfBoundsException contract and does not wrap it`() {
        val serde = JacksonSerde.withDefaults()
        // A bad offset is a programming error, not a serde failure: it must remain an
        // IndexOutOfBoundsException, never get re-wrapped as a SerdeException.
        val tooSmall = ByteArray(0)
        val ex = assertFailsWith<IndexOutOfBoundsException> { serde.serializer.serialize(Inner("a", 1), tooSmall) }
        assertFalse(
            SerdeException::class.java.isInstance(ex),
            "a bad-offset error must not be re-wrapped as a SerdeException",
        )
        assertFalse(
            SerdeException::class.java.isInstance(ex.cause),
            "an IndexOutOfBoundsException must not chain a SerdeException cause",
        )
    }

    private class TrackingOutputStream(private val sink: ByteArrayOutputStream) : java.io.OutputStream() {
        var bytesWritten: Int = 0
            private set
        var closeCount: Int = 0
            private set

        override fun write(b: Int) {
            sink.write(b)
            bytesWritten += 1
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            sink.write(b, off, len)
            bytesWritten += len
        }

        override fun close() {
            closeCount += 1
            super.close()
        }
    }

    private class TrackingInputStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var closeCount: Int = 0
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len)

        override fun available(): Int = delegate.available()

        override fun close() {
            closeCount += 1
            delegate.close()
        }
    }
}
