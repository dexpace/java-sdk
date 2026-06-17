/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.Serializer
import org.dexpace.sdk.io.OkioIoProvider
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MultipartBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        for (path in tempFiles.reversed()) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Throwable) {
                // best effort
            }
        }
        tempFiles.clear()
    }

    private fun tempFile(content: ByteArray): Path {
        val p = Files.createTempFile("multipart-test", ".bin")
        Files.write(p, content)
        tempFiles.add(p)
        return p
    }

    /** A trivial [Serde] that encodes a String as raw UTF-8 — no Jackson in core. */
    private object StringSerde : Serde {
        override val serializer: Serializer =
            object : Serializer {
                override fun serialize(input: Any): String = input.toString()

                override fun serializeToByteArray(input: Any): ByteArray = input.toString().toByteArray(Charsets.UTF_8)

                override fun serialize(
                    input: Any,
                    outputStream: OutputStream,
                ) {
                    outputStream.write(serializeToByteArray(input))
                }

                override fun serialize(
                    input: Any,
                    buffer: ByteArray,
                    offset: Int,
                ): Int {
                    val bytes = serializeToByteArray(input)
                    System.arraycopy(bytes, 0, buffer, offset, bytes.size)
                    return bytes.size
                }
            }

        override val deserializer: Deserializer =
            object : Deserializer {
                override fun <T> deserialize(
                    input: String,
                    type: Class<T>,
                ): T = throw UnsupportedOperationException()

                override fun <T> deserialize(
                    input: ByteArray,
                    type: Class<T>,
                ): T = throw UnsupportedOperationException()

                override fun <T> deserialize(
                    inputStream: java.io.InputStream,
                    type: Class<T>,
                ): T = throw UnsupportedOperationException()
            }
    }

    private fun drain(body: RequestBody): ByteArray {
        val buf = Io.provider.buffer()
        body.writeTo(buf)
        return buf.snapshot()
    }

    // ---------------------------------------------------------------------
    // Boundary + content type
    // ---------------------------------------------------------------------

    @Test
    fun `generated boundary is unique per call and token-safe`() {
        val a = MultipartBody.generateBoundary()
        val b = MultipartBody.generateBoundary()
        assertNotEquals(a, b)
        assertTrue(a.startsWith("dexpace-"))
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test
    fun `media type is multipart form-data with boundary parameter`() {
        val body =
            MultipartBody.builder()
                .boundary("abc123")
                .addPart("field", "value", StringSerde)
                .build()
        val mediaType = body.mediaType()
        assertEquals("multipart", mediaType.type)
        assertEquals("form-data", mediaType.subtype)
        assertEquals("abc123", mediaType.parameters["boundary"])
        assertEquals("multipart/form-data;boundary=abc123", mediaType.toString())
    }

    @Test
    fun `empty body is rejected`() {
        assertFailsWith<IllegalArgumentException> { MultipartBody.builder().build() }
    }

    @Test
    fun `empty boundary is rejected`() {
        assertFailsWith<IllegalArgumentException> { MultipartBody.builder().boundary("") }
    }

    // ---------------------------------------------------------------------
    // contentLength matches writeTo — the core invariant
    // ---------------------------------------------------------------------

    @Test
    fun `contentLength matches bytes written for value parts`() {
        val body =
            MultipartBody.builder()
                .boundary("len-check")
                .addPart("a", "hello", StringSerde, CommonMediaTypes.TEXT_PLAIN)
                .addPart("b", "world", StringSerde)
                .build()
        val written = drain(body)
        assertEquals(written.size.toLong(), body.contentLength())
    }

    @Test
    fun `contentLength matches bytes written for mixed file and value parts`() {
        val file = tempFile("file-contents-1234567890".toByteArray())
        val body =
            MultipartBody.builder()
                .boundary("mixed-check")
                .addPart("meta", "some-json", StringSerde, CommonMediaTypes.APPLICATION_JSON)
                .addFile("upload", file, "report.bin", CommonMediaTypes.APPLICATION_OCTET_STREAM)
                .build()
        val written = drain(body)
        assertEquals(written.size.toLong(), body.contentLength())
    }

    @Test
    fun `contentLength is unknown when a part body length is unknown`() {
        val unknownBody =
            object : RequestBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("x")
                }
            }
        val body =
            MultipartBody.builder()
                .boundary("unknown")
                .addPart(MultipartBody.Part.create("a", unknownBody))
                .build()
        assertEquals(-1L, body.contentLength())
    }

    // ---------------------------------------------------------------------
    // Wire-format round trip
    // ---------------------------------------------------------------------

    @Test
    fun `multi-part wire format is correct`() {
        val body =
            MultipartBody.builder()
                .boundary("BOUNDARY")
                .addPart("name", "Omar", StringSerde, CommonMediaTypes.TEXT_PLAIN)
                .build()
        val wire = String(drain(body), Charsets.UTF_8)
        val expected =
            "--BOUNDARY\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Omar\r\n" +
                "--BOUNDARY--\r\n"
        assertEquals(expected, wire)
    }

    @Test
    fun `file part emits filename in content disposition`() {
        val file = tempFile("PNGDATA".toByteArray())
        val body =
            MultipartBody.builder()
                .boundary("B")
                .addFile("image", file, "photo.png", CommonMediaTypes.IMAGE_PNG)
                .build()
        val wire = String(drain(body), Charsets.UTF_8)
        assertContains(wire, "Content-Disposition: form-data; name=\"image\"; filename=\"photo.png\"")
        assertContains(wire, "Content-Type: image/png")
        assertContains(wire, "PNGDATA")
    }

    @Test
    fun `closing delimiter terminates the body exactly once`() {
        val body =
            MultipartBody.builder()
                .boundary("END")
                .addPart("a", "1", StringSerde)
                .addPart("b", "2", StringSerde)
                .build()
        val wire = String(drain(body), Charsets.UTF_8)
        assertTrue(wire.endsWith("--END--\r\n"))
        // The closing delimiter appears once; the opening delimiters appear per part.
        assertEquals(2, "--END\r\n".toRegex().findAll(wire).count())
    }

    @Test
    fun `name and filename with special characters are escaped`() {
        val file = tempFile("x".toByteArray())
        val body =
            MultipartBody.builder()
                .boundary("B")
                .addFile("na\"me", file, "a\r\nb\"c.txt")
                .build()
        val wire = String(drain(body), Charsets.UTF_8)
        assertContains(wire, "name=\"na%22me\"")
        assertContains(wire, "filename=\"a%0D%0Ab%22c.txt\"")
        // No raw CR/LF smuggled into the header beyond the framing CRLFs.
        assertFalse(wire.contains("a\r\nb"))
    }

    // ---------------------------------------------------------------------
    // Replayability
    // ---------------------------------------------------------------------

    @Test
    fun `body of all replayable parts is replayable and produces identical bytes`() {
        val file = tempFile("filedata".toByteArray())
        val body =
            MultipartBody.builder()
                .boundary("REPLAY")
                .addPart("a", "value", StringSerde)
                .addFile("f", file)
                .build()
        assertTrue(body.isReplayable())
        assertSame(body, body.toReplayable())
        val first = drain(body)
        val second = drain(body)
        assertEquals(String(first, Charsets.UTF_8), String(second, Charsets.UTF_8))
    }

    @Test
    fun `body with a non-replayable part is not replayable but can be buffered`() {
        val oneShot =
            object : RequestBody() {
                override fun mediaType(): MediaType = CommonMediaTypes.TEXT_PLAIN

                override fun contentLength(): Long = 8

                override fun isReplayable(): Boolean = false

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("streamed")
                }
            }
        val body =
            MultipartBody.builder()
                .boundary("ONESHOT")
                .addPart(MultipartBody.Part.create("a", oneShot))
                .build()
        assertFalse(body.isReplayable())
        val replayable = body.toReplayable()
        assertNotEquals(body, replayable)
        assertTrue(replayable.isReplayable())
        // The buffered copy is byte-identical to a fresh render would have been.
        val rendered = String(drain(replayable), Charsets.UTF_8)
        assertContains(rendered, "streamed")
        assertContains(rendered, "--ONESHOT--\r\n")
    }

    @Test
    fun `serialized part uses the serde and carries the declared media type`() {
        val body =
            MultipartBody.builder()
                .boundary("S")
                .addPart("payload", "raw-value", StringSerde, CommonMediaTypes.APPLICATION_JSON)
                .build()
        val wire = String(drain(body), Charsets.UTF_8)
        assertContains(wire, "Content-Type: application/json")
        assertContains(wire, "raw-value")
    }

    @Test
    fun `parts list is unmodifiable`() {
        val body = MultipartBody.builder().boundary("B").addPart("a", "1", StringSerde).build()
        assertEquals(1, body.parts.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (body.parts as MutableList<MultipartBody.Part>).clear()
        }
    }

    @Test
    fun `empty part name is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MultipartBody.Part.serialized("", "v", StringSerde)
        }
    }
}
