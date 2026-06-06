/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder
import org.dexpace.sdk.core.serde.Tristate
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JacksonSerdeTest {
    // Kotlin data class with a nested object, list, java.time.Instant, Optional<T>, Tristate<T>.
    data class Inner(val tag: String, val score: Int)

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
    fun `serialize to ByteArray buffer fits when sized correctly`() {
        val serde = JacksonSerde.withDefaults()
        val payload = Inner("z", 9)
        val expected = serde.serializer.serializeToByteArray(payload)
        val buffer = ByteArray(expected.size + 16)
        serde.serializer.serialize(payload, buffer)
        // First N bytes match the canonical bytes.
        val prefix = buffer.copyOfRange(0, expected.size)
        assertTrue(prefix.contentEquals(expected))
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
    fun `JacksonSerde from caller-supplied mapper uses that mapper`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        val serde = JacksonSerde.from(mapper)
        // The same mapper instance is exposed via the `mapper` property.
        assertTrue(serde.mapper === mapper)
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
}
