/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.util.concurrent.atomic.AtomicInteger
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

class ResponseTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.test/p")
            .method(Method.GET)
            .build()

    private fun simpleBody(payload: String = "abc"): ResponseBody {
        val source = Io.provider.source(payload.toByteArray())
        return ResponseBody.create(source, null, payload.length.toLong())
    }

    @Test
    fun `newBuilder round trip preserves all fields`() {
        val original =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .message("OK")
                .addHeader("X-A", "1")
                .build()

        val copy = original.newBuilder().build()

        assertEquals(original.request, copy.request)
        assertEquals(original.protocol, copy.protocol)
        assertEquals(original.status, copy.status)
        assertEquals(original.message, copy.message)
        assertEquals(original.headers, copy.headers)
        assertEquals(original.body, copy.body)
        assertEquals(original, copy)
    }

    @Test
    fun `newBuilder allows changes without disturbing source`() {
        val original =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build()

        val mutated =
            original.newBuilder()
                .status(Status.CREATED)
                .build()

        assertEquals(Status.OK, original.status)
        assertEquals(Status.CREATED, mutated.status)
        assertNotSame(original, mutated)
    }

    @Test
    fun `static factory returns fresh empty builders`() {
        val b1 = Response.builder()
        val b2 = Response.builder()
        assertNotSame(b1, b2)
    }

    @Test
    fun `ResponseBuilder copy constructor copies all fields`() {
        val body = simpleBody()
        val source =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_2)
                .status(Status.CREATED)
                .message("Created")
                .addHeader("X-A", "1")
                .body(body)
                .build()

        val rebuilt = Response.ResponseBuilder(source).build()

        assertEquals(source.request, rebuilt.request)
        assertEquals(source.protocol, rebuilt.protocol)
        assertEquals(source.status, rebuilt.status)
        assertEquals(source.message, rebuilt.message)
        assertEquals(source.headers, rebuilt.headers)
        assertSame(body, rebuilt.body)
    }

    @Test
    fun `request setter wires the originating request`() {
        val r = request()
        val resp =
            Response.builder()
                .request(r)
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build()
        assertSame(r, resp.request)
    }

    @Test
    fun `protocol setter wires the negotiated protocol`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.QUIC)
                .status(Status.OK)
                .build()
        assertEquals(Protocol.QUIC, resp.protocol)
    }

    @Test
    fun `status setter wires the status`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.NOT_FOUND)
                .build()
        assertEquals(Status.NOT_FOUND, resp.status)
    }

    @Test
    fun `message setter wires the reason phrase`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .message("All good")
                .build()
        assertEquals("All good", resp.message)
    }

    @Test
    fun `addHeader name+value adds the header`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-A", "v1")
                .build()
        assertEquals(listOf("v1"), resp.headers.values("X-A"))
    }

    @Test
    fun `addHeader with list adds multiple values`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-Multi", listOf("a", "b", "c"))
                .build()
        assertEquals(listOf("a", "b", "c"), resp.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader single replaces all values`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-A", "old")
                .setHeader("X-A", "new")
                .build()
        assertEquals(listOf("new"), resp.headers.values("X-A"))
    }

    @Test
    fun `setHeader with list replaces all values`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-A", "old")
                .setHeader("X-A", listOf("x", "y"))
                .build()
        assertEquals(listOf("x", "y"), resp.headers.values("X-A"))
    }

    @Test
    fun `removeHeader by name drops all values`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-A", "1")
                .addHeader("X-A", "2")
                .removeHeader("X-A")
                .build()
        assertEquals(emptyList(), resp.headers.values("X-A"))
    }

    @Test
    fun `removeHeader by HttpHeaderName drops all values`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("ETag", "abc")
                .removeHeader(HttpHeaderName.ETAG)
                .build()
        assertEquals(emptyList(), resp.headers.values("ETag"))
    }

    @Test
    fun `headers replaces the entire header set`() {
        val headers = Headers.builder().add("X-A", "1").add("X-B", "2").build()
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .addHeader("X-Stale", "x")
                .headers(headers)
                .build()
        assertEquals(listOf("1"), resp.headers.values("X-A"))
        assertEquals(listOf("2"), resp.headers.values("X-B"))
        assertEquals(emptyList(), resp.headers.values("X-Stale"))
    }

    @Test
    fun `body setter wires the body`() {
        val b = simpleBody()
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(b)
                .build()
        assertSame(b, resp.body)
    }

    @Test
    fun `body setter accepts null`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(null)
                .build()
        assertNull(resp.body)
    }

    @Test
    fun `build throws when request missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                Response.builder().protocol(Protocol.HTTP_1_1).status(Status.OK).build()
            }
        assertEquals("request is required", ex.message)
    }

    @Test
    fun `build throws when protocol missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                Response.builder().request(request()).status(Status.OK).build()
            }
        assertEquals("protocol is required", ex.message)
    }

    @Test
    fun `build throws when status missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                Response.builder().request(request()).protocol(Protocol.HTTP_1_1).build()
            }
        assertEquals("status is required", ex.message)
    }

    @Test
    fun `close delegates to the body`() {
        val closed = AtomicInteger(0)
        val body =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = throw UnsupportedOperationException()

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(body)
                .build()

        resp.close()
        assertEquals(1, closed.get())
    }

    @Test
    fun `close is a no-op when body is null`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build()
        // Should not throw.
        resp.close()
        assertNull(resp.body)
    }

    // ---- isSuccessful (T2) -------------------------------------------------------------

    @Test
    fun `isSuccessful is true for 2xx status`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build()
        assertTrue(resp.isSuccessful)
    }

    @Test
    fun `isSuccessful is true for 201 CREATED`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.CREATED)
                .build()
        assertTrue(resp.isSuccessful)
    }

    @Test
    fun `isSuccessful is false for 4xx status`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.NOT_FOUND)
                .build()
        assertFalse(resp.isSuccessful)
    }

    @Test
    fun `isSuccessful is false for 5xx status`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.INTERNAL_SERVER_ERROR)
                .build()
        assertFalse(resp.isSuccessful)
    }

    @Test
    fun `isSuccessful is false for 3xx redirect`() {
        val resp =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.MOVED_PERMANENTLY)
                .build()
        assertFalse(resp.isSuccessful)
    }

    // ---- status-class delegation (isInformational / isRedirect / isClientError / …) ---------

    private fun responseWith(status: Status): Response =
        Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(status)
            .build()

    @Test
    fun `status-class predicates delegate to Status for representative codes`() {
        // Each predicate must mirror Status's own classification. Representative codes:
        // 100 informational, 301 redirect, 404 client error, 503 server error, 200 success.
        val informational = responseWith(Status.fromCode(100))
        assertTrue(informational.isInformational)
        assertFalse(informational.isRedirect)
        assertFalse(informational.isClientError)
        assertFalse(informational.isServerError)
        assertFalse(informational.isError)

        val redirect = responseWith(Status.fromCode(301))
        assertFalse(redirect.isInformational)
        assertTrue(redirect.isRedirect)
        assertFalse(redirect.isClientError)
        assertFalse(redirect.isServerError)
        assertFalse(redirect.isError)

        val clientError = responseWith(Status.fromCode(404))
        assertFalse(clientError.isRedirect)
        assertTrue(clientError.isClientError)
        assertFalse(clientError.isServerError)
        assertTrue(clientError.isError)

        val serverError = responseWith(Status.fromCode(503))
        assertFalse(serverError.isClientError)
        assertTrue(serverError.isServerError)
        assertTrue(serverError.isError)

        val success = responseWith(Status.fromCode(200))
        assertFalse(success.isInformational)
        assertFalse(success.isRedirect)
        assertFalse(success.isClientError)
        assertFalse(success.isServerError)
        assertFalse(success.isError)
    }
}

class ResponseBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `create returns a body whose source emits the supplied bytes`() {
        val payload = "hello".toByteArray()
        val source = Io.provider.source(payload)
        val type = MediaType.parse("text/plain")
        val body = ResponseBody.create(source, type, payload.size.toLong())

        assertSame(type, body.mediaType())
        assertEquals(payload.size.toLong(), body.contentLength())
        val read = body.source().readByteArray()
        assertContentEquals(payload, read)
    }

    @Test
    fun `create with default arguments produces unknown media type and content length`() {
        val source = Io.provider.source("x".toByteArray())
        val body = ResponseBody.create(source)
        assertNull(body.mediaType())
        assertEquals(-1L, body.contentLength())
    }

    @Test
    fun `create body close closes the underlying source`() {
        // The okio source wraps a `Buffer` underneath — close is idempotent and safe.
        val source = Io.provider.source("data".toByteArray())
        val body = ResponseBody.create(source)
        // Read the bytes, then close.
        body.source().readByteArray()
        body.close()
        // A second close must not throw (idempotency contract).
        body.close()
    }

    @Test
    fun `create body source returns the same instance on every call`() {
        val source = Io.provider.source("x".toByteArray())
        val body = ResponseBody.create(source)
        assertSame(source, body.source())
        assertSame(source, body.source())
    }

    @Test
    fun `abstract close contract is honored by subclass`() {
        val closed = AtomicInteger(0)
        val body =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = throw UnsupportedOperationException()

                override fun close() {
                    closed.incrementAndGet()
                }
            }
        body.close()
        body.close()
        assertEquals(2, closed.get())
    }

    @Test
    fun `body whose source is not invoked can still be closed`() {
        val source = Io.provider.source("data".toByteArray())
        val body = ResponseBody.create(source)
        body.close()
        // Reading after close throws via the wrapped source — this just establishes the
        // "close before read" path is supported.
        assertTrue(true)
    }

    // ---- string() and bytes() (T2) ----------------------------------------------------

    @Test
    fun `string() reads entire body as UTF-8 by default`() {
        val payload = "Hello, World!"
        val source = Io.provider.source(payload.toByteArray(Charsets.UTF_8))
        val body = ResponseBody.create(source)
        assertEquals(payload, body.string())
    }

    @Test
    fun `string() accepts an explicit charset`() {
        val payload = "café"
        val bytes = payload.toByteArray(Charsets.ISO_8859_1)
        val source = Io.provider.source(bytes)
        val body = ResponseBody.create(source)
        assertEquals(payload, body.string(Charsets.ISO_8859_1))
    }

    @Test
    fun `bytes() reads all raw bytes from body`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val source = Io.provider.source(payload)
        val body = ResponseBody.create(source)
        assertContentEquals(payload, body.bytes())
    }

    @Test
    fun `string() on empty body returns empty string`() {
        val source = Io.provider.source(ByteArray(0))
        val body = ResponseBody.create(source)
        assertEquals("", body.string())
    }

    @Test
    fun `bytes() on empty body returns empty array`() {
        val source = Io.provider.source(ByteArray(0))
        val body = ResponseBody.create(source)
        assertContentEquals(ByteArray(0), body.bytes())
    }

    // ---- string() default charset derives from the media type ----------------------------

    @Test
    fun `no-arg string() decodes using the charset declared in the media type`() {
        // 0xE9 is 'é' in ISO-8859-1 but an invalid lone byte in UTF-8. With the media type
        // declaring charset=ISO-8859-1, the no-arg string() must latin-1-decode it to "é",
        // not fall back to UTF-8.
        val bytes = byteArrayOf(0xE9.toByte())
        val mediaType = MediaType.parse("text/plain; charset=ISO-8859-1")
        val source = Io.provider.source(bytes)
        val body = ResponseBody.create(source, mediaType, bytes.size.toLong())
        assertEquals("é", body.string())
    }

    @Test
    fun `explicit charset argument overrides the media type charset`() {
        // Bytes are the UTF-8 encoding of "é" (0xC3 0xA9). Even though the media type declares
        // ISO-8859-1 (under which those bytes decode to "Ã©"), an explicit string(UTF_8) must use
        // UTF-8 and yield "é" — proving the argument takes precedence over the media type charset.
        val bytes = "é".toByteArray(Charsets.UTF_8)
        val mediaType = MediaType.parse("text/plain; charset=ISO-8859-1")
        val source = Io.provider.source(bytes)
        val body = ResponseBody.create(source, mediaType, bytes.size.toLong())
        assertEquals("é", body.string(Charsets.UTF_8))
    }
}
