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
import org.dexpace.sdk.io.OkioIoProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequestBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun drain(body: RequestBody): ByteArray {
        val buf = Io.provider.buffer()
        body.writeTo(buf)
        return buf.snapshot()
    }

    // ---------------------------------------------------------------------
    // Abstract RequestBody defaults
    // ---------------------------------------------------------------------

    @Test
    fun `default isReplayable returns false`() {
        val body =
            object : RequestBody() {
                override fun mediaType(): MediaType? = null

                override fun writeTo(sink: BufferedSink) {
                    sink.write(byteArrayOf(1, 2))
                }
            }
        assertFalse(body.isReplayable())
    }

    @Test
    fun `default contentLength returns minus one`() {
        val body =
            object : RequestBody() {
                override fun mediaType(): MediaType? = null

                override fun writeTo(sink: BufferedSink) {}
            }
        assertEquals(-1L, body.contentLength())
    }

    @Test
    fun `default toReplayable drains writeTo into a buffer-backed body`() {
        val payload = "abc".toByteArray()
        val source =
            object : RequestBody() {
                override fun mediaType(): MediaType? = null

                override fun writeTo(sink: BufferedSink) {
                    sink.write(payload)
                }
            }

        val replayable = source.toReplayable(Io.provider)

        // Returned body must NOT be the source — it is a fresh BufferRequestBody.
        assertNotSame(source, replayable)
        assertTrue(replayable.isReplayable())
        // Multiple drains return the same bytes — exercising the buffer-peek path.
        assertContentEquals(payload, drain(replayable))
        assertContentEquals(payload, drain(replayable))
    }

    @Test
    fun `toReplayable returns same body when already replayable`() {
        val replayable = RequestBody.create("x".toByteArray())
        assertSame(replayable, replayable.toReplayable(Io.provider))
    }

    // ---------------------------------------------------------------------
    // ByteArrayRequestBody
    // ---------------------------------------------------------------------

    @Test
    fun `byte array body returns content length and media type and writes bytes`() {
        val type = MediaType.parse("application/json")
        val body = RequestBody.create("hello".toByteArray(), type)
        assertEquals(5L, body.contentLength())
        assertSame(type, body.mediaType())
        assertTrue(body.isReplayable())
        assertContentEquals("hello".toByteArray(), drain(body))
    }

    @Test
    fun `byte array body preserves content across multiple writes`() {
        val body = RequestBody.create("retry-me".toByteArray())
        repeat(3) {
            assertContentEquals("retry-me".toByteArray(), drain(body))
        }
    }

    @Test
    fun `byte array body toReplayable returns self`() {
        val body = RequestBody.create("x".toByteArray())
        assertSame(body, body.toReplayable(Io.provider))
    }

    // ---------------------------------------------------------------------
    // BufferRequestBody (created via Buffer factory)
    // ---------------------------------------------------------------------

    @Test
    fun `buffer body exposes content length media type and is replayable`() {
        val buf = Io.provider.buffer()
        buf.write("payload".toByteArray())
        val type = MediaType.parse("text/plain")
        val body = RequestBody.create(buf, type)

        assertEquals("payload".length.toLong(), body.contentLength())
        assertSame(type, body.mediaType())
        assertTrue(body.isReplayable())
    }

    @Test
    fun `buffer body writes peeked bytes and survives repeated writes`() {
        val buf = Io.provider.buffer()
        buf.write("repeatable".toByteArray())
        val body = RequestBody.create(buf)

        // The buffer body uses peek(), so successive writes must produce identical output.
        repeat(3) {
            assertContentEquals("repeatable".toByteArray(), drain(body))
        }
    }

    @Test
    fun `buffer body toReplayable returns self`() {
        val buf = Io.provider.buffer()
        buf.write("x".toByteArray())
        val body = RequestBody.create(buf)
        assertSame(body, body.toReplayable(Io.provider))
    }

    @Test
    fun `buffer body explicit content length is preserved`() {
        val buf = Io.provider.buffer()
        buf.write("ab".toByteArray())
        val body = RequestBody.create(buf, null, contentLength = 99L)
        assertEquals(99L, body.contentLength())
    }

    // ---------------------------------------------------------------------
    // BufferedSourceRequestBody
    // ---------------------------------------------------------------------

    @Test
    fun `buffered source body is not replayable`() {
        val body = RequestBody.create(Io.provider.source("data".toByteArray()))
        assertFalse(body.isReplayable())
        assertEquals(-1L, body.contentLength())
        assertNull(body.mediaType())
    }

    @Test
    fun `buffered source body explicit content length is preserved`() {
        val body = RequestBody.create(Io.provider.source("data".toByteArray()), null, 4L)
        assertEquals(4L, body.contentLength())
    }

    @Test
    fun `buffered source body drains on first write and fails on second`() {
        val body = RequestBody.create(Io.provider.source("data".toByteArray()))
        assertContentEquals("data".toByteArray(), drain(body))
        val ex = assertFailsWith<IllegalStateException> { drain(body) }
        // Error message hints at the contract violation.
        assertTrue(ex.message?.contains("writeTo was already called") == true)
    }

    @Test
    fun `buffered source body toReplayable drains into a buffer`() {
        val body = RequestBody.create(Io.provider.source("ten-bytes!".toByteArray()))
        val replayable = body.toReplayable(Io.provider)
        assertTrue(replayable.isReplayable())
        assertContentEquals("ten-bytes!".toByteArray(), drain(replayable))
        assertContentEquals("ten-bytes!".toByteArray(), drain(replayable))
    }

    // ---------------------------------------------------------------------
    // OneShotInputStreamRequestBody (mark NOT supported)
    // ---------------------------------------------------------------------

    private class NoMarkInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len)

        override fun markSupported(): Boolean = false
    }

    @Test
    fun `one shot input stream body is non-replayable`() {
        val body = RequestBody.create(NoMarkInputStream(ByteArrayInputStream("hello".toByteArray())), 5L)
        assertFalse(body.isReplayable())
        assertEquals(5L, body.contentLength())
    }

    @Test
    fun `one shot input stream body second writeTo throws IllegalStateException`() {
        val body = RequestBody.create(NoMarkInputStream(ByteArrayInputStream("hello".toByteArray())), 5L)
        assertContentEquals("hello".toByteArray(), drain(body))
        val ex = assertFailsWith<IllegalStateException> { drain(body) }
        assertTrue(ex.message?.contains("already called") == true)
    }

    @Test
    fun `one shot input stream body toReplayable drains into buffer body`() {
        val body = RequestBody.create(NoMarkInputStream(ByteArrayInputStream("payload".toByteArray())), 7L)
        val replayable = body.toReplayable(Io.provider)
        assertTrue(replayable.isReplayable())
        assertContentEquals("payload".toByteArray(), drain(replayable))
        assertContentEquals("payload".toByteArray(), drain(replayable))
    }

    // ---------------------------------------------------------------------
    // ResettableInputStreamRequestBody (mark/reset supported)
    // ---------------------------------------------------------------------

    @Test
    fun `mark supported stream produces a replayable body`() {
        val body = RequestBody.create(ByteArrayInputStream("hello".toByteArray()), 5L)
        assertTrue(body.isReplayable())
        assertEquals(5L, body.contentLength())
    }

    @Test
    fun `mark supported body exposes media type`() {
        val type = MediaType.parse("application/octet-stream")
        val body = RequestBody.create(ByteArrayInputStream("x".toByteArray()), 1L, type)
        assertSame(type, body.mediaType())
    }

    @Test
    fun `mark supported body second writeTo replays via mark reset`() {
        val body = RequestBody.create(ByteArrayInputStream("retry-ok".toByteArray()), 8L)
        assertContentEquals("retry-ok".toByteArray(), drain(body))
        assertContentEquals("retry-ok".toByteArray(), drain(body))
        assertContentEquals("retry-ok".toByteArray(), drain(body))
    }

    @Test
    fun `mark supported body toReplayable returns self`() {
        val body = RequestBody.create(ByteArrayInputStream("x".toByteArray()), 1L)
        assertSame(body, body.toReplayable(Io.provider))
    }

    // ---------------------------------------------------------------------
    // Factory companion overloads
    // ---------------------------------------------------------------------

    @Test
    fun `string content factory encodes UTF-8 by default`() {
        val body = RequestBody.create("héllo")
        assertEquals("héllo".toByteArray(Charsets.UTF_8).size.toLong(), body.contentLength())
        assertContentEquals("héllo".toByteArray(Charsets.UTF_8), drain(body))
    }

    @Test
    fun `string content factory honors charset argument`() {
        val body = RequestBody.create("héllo", null, Charsets.ISO_8859_1)
        assertContentEquals("héllo".toByteArray(Charsets.ISO_8859_1), drain(body))
    }

    @Test
    fun `byte array factory accepts null media type`() {
        val body = RequestBody.create("ab".toByteArray())
        assertNull(body.mediaType())
    }

    @Test
    fun `input stream factory requires non-negative length`() {
        assertFailsWith<IllegalArgumentException> {
            RequestBody.create(ByteArrayInputStream(ByteArray(0)), -1L)
        }
    }

    @Test
    fun `form data factory encodes pairs with url encoding`() {
        val body = RequestBody.create(linkedMapOf("a b" to "c&d", "x" to "1 2"))
        val expected = "a+b=c%26d&x=1+2".toByteArray(Charsets.UTF_8)
        assertContentEquals(expected, drain(body))
        assertEquals(CommonMediaTypes.APPLICATION_FORM_URLENCODED, body.mediaType())
    }

    @Test
    fun `form data factory honors charset`() {
        val body = RequestBody.create(linkedMapOf("k" to "é"), Charsets.ISO_8859_1)
        // ISO-8859-1 maps é to single byte 0xE9 → url-encoded as %E9.
        val expected = "k=%E9".toByteArray(Charsets.ISO_8859_1)
        assertContentEquals(expected, drain(body))
    }
}
