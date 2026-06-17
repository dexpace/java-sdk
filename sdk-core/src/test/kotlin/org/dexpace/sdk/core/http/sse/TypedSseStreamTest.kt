/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.io.OkioIoProvider
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TypedSseStreamTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun source(text: String): BufferedSource = Io.provider.source(text.toByteArray(Charsets.UTF_8))

    /** Builds a single `data:`-only SSE event block carrying [payload]. */
    private fun event(payload: String): String = "data: $payload\n\n"

    /** Toy JSON for a [Chunk] with [text]. */
    private fun chunkJson(text: String): String = """{"text":"$text"}"""

    private fun stream(
        text: String,
        resource: Closeable = CountingCloseable(),
    ): SseStream = SseStream.from(source(text), resource)

    private class CountingCloseable : Closeable {
        val closeCount = AtomicInteger(0)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private data class Chunk(val text: String)

    /**
     * Minimal [Deserializer] standing in for a real adapter (e.g. sdk-serde-jackson): it
     * records every decode call and parses the toy `{"text":"..."}` shape into [Chunk],
     * letting the test assert both typed output and the Serde SPI being exercised lazily.
     */
    private class RecordingDeserializer : Deserializer {
        val decoded = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> deserialize(
            input: String,
            type: Class<T>,
        ): T {
            decoded.add(input)
            val text = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(input)?.groupValues?.get(1) ?: ""
            return Chunk(text) as T
        }

        override fun <T> deserialize(
            input: ByteArray,
            type: Class<T>,
        ): T = deserialize(String(input, Charsets.UTF_8), type)

        override fun <T> deserialize(
            inputStream: InputStream,
            type: Class<T>,
        ): T = deserialize(inputStream.readBytes().toString(Charsets.UTF_8), type)
    }

    /** Endpoint mapper: `[DONE]` ends the stream, comment-only events skip, else decode. */
    private fun chunkMapper(deserializer: Deserializer): SseEventMapper<Chunk> =
        SseEventMapper { _, data ->
            when {
                data == "[DONE]" -> SseEventMapper.Result.Done
                data.isEmpty() -> SseEventMapper.Result.Skip
                else -> SseEventMapper.Result.Value(deserializer.deserialize(data, Chunk::class.java))
            }
        }

    @Test
    fun `maps raw events to typed models via the deserializer`() {
        val deser = RecordingDeserializer()
        val raw = stream(event(chunkJson("hi")) + event(chunkJson("bye")))

        val chunks = TypedSseStream(raw, chunkMapper(deser)).toList()

        assertEquals(listOf(Chunk("hi"), Chunk("bye")), chunks)
        assertEquals(2, deser.decoded.size)
    }

    @Test
    fun `done sentinel ends iteration and closes the underlying stream`() {
        val resource = CountingCloseable()
        val deser = RecordingDeserializer()
        val raw = stream(event(chunkJson("a")) + event("[DONE]") + event(chunkJson("never")), resource)

        val chunks = TypedSseStream(raw, chunkMapper(deser)).toList()

        // Only the pre-sentinel element is yielded; the post-sentinel event is never decoded.
        assertEquals(listOf(Chunk("a")), chunks)
        assertEquals(1, deser.decoded.size)
        // Reaching the done-sentinel closes the underlying stream/resource.
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `skip results are dropped and do not surface to the caller`() {
        val deser = RecordingDeserializer()
        // The middle event is a comment-only keep-alive: its data is empty, so the mapper skips it.
        val raw = stream(event(chunkJson("a")) + ":keep-alive\n\n" + event(chunkJson("b")))

        val chunks = TypedSseStream(raw, chunkMapper(deser)).toList()

        assertEquals(listOf(Chunk("a"), Chunk("b")), chunks)
    }

    @Test
    fun `decode happens lazily per element on consume`() {
        val deser = RecordingDeserializer()
        val raw = stream(event(chunkJson("a")) + event(chunkJson("b")))
        val iter = TypedSseStream(raw, chunkMapper(deser)).iterator()

        // Nothing decoded before the first pull.
        assertEquals(0, deser.decoded.size)
        assertEquals(Chunk("a"), iter.next())
        // Only the first element has been decoded; the second is untouched until pulled.
        assertEquals(1, deser.decoded.size)
        assertEquals(Chunk("b"), iter.next())
        assertEquals(2, deser.decoded.size)
    }

    @Test
    fun `close propagates from the typed adapter to the underlying resource`() {
        val resource = CountingCloseable()
        val raw = stream(event(chunkJson("a")), resource)
        val typed = TypedSseStream(raw, chunkMapper(RecordingDeserializer()))

        typed.close()
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `mapper exceptions propagate and leave the stream closeable`() {
        val resource = CountingCloseable()
        val raw = stream(event("boom"), resource)
        val typed =
            TypedSseStream<Chunk>(raw) { _, _ ->
                error("decode failed")
            }

        assertFailsWith<IllegalStateException> { typed.iterator().next() }
        // The underlying stream stayed open; the caller can still release it.
        typed.close()
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `result factory helpers build the expected variants`() {
        val value = SseEventMapper.value(Chunk("v"))
        assertTrue(value is SseEventMapper.Result.Value)
        assertEquals(Chunk("v"), value.model)
        assertTrue(SseEventMapper.skip() === SseEventMapper.Result.Skip)
        assertTrue(SseEventMapper.done() === SseEventMapper.Result.Done)
    }

    @Test
    fun `typed extension wraps a stream`() {
        val deser = RecordingDeserializer()
        val chunks = stream(event(chunkJson("z"))).typed(chunkMapper(deser)).toList()
        assertTrue(chunks.contains(Chunk("z")))
    }
}
