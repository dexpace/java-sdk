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
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResponseTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun request(): Request = Request.builder()
        .url("https://api.example.test/p")
        .method(Method.GET)
        .build()

    private fun simpleBody(payload: String = "abc"): ResponseBody {
        val source = Io.provider.source(payload.toByteArray())
        return ResponseBody.create(source, null, payload.length.toLong())
    }

    @Test
    fun `newBuilder round trip preserves all fields`() {
        val original = Response.builder()
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
        val original = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .build()

        val mutated = original.newBuilder()
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
        val source = Response.builder()
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
        val resp = Response.builder()
            .request(r)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .build()
        assertSame(r, resp.request)
    }

    @Test
    fun `protocol setter wires the negotiated protocol`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.QUIC)
            .status(Status.OK)
            .build()
        assertEquals(Protocol.QUIC, resp.protocol)
    }

    @Test
    fun `status setter wires the status`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.NOT_FOUND)
            .build()
        assertEquals(Status.NOT_FOUND, resp.status)
    }

    @Test
    fun `message setter wires the reason phrase`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .message("All good")
            .build()
        assertEquals("All good", resp.message)
    }

    @Test
    fun `addHeader name+value adds the header`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .addHeader("X-A", "v1")
            .build()
        assertEquals(listOf("v1"), resp.headers.values("X-A"))
    }

    @Test
    fun `addHeader with list adds multiple values`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .addHeader("X-Multi", listOf("a", "b", "c"))
            .build()
        assertEquals(listOf("a", "b", "c"), resp.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader single replaces all values`() {
        val resp = Response.builder()
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
        val resp = Response.builder()
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
        val resp = Response.builder()
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
        val resp = Response.builder()
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
        val resp = Response.builder()
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
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(b)
            .build()
        assertSame(b, resp.body)
    }

    @Test
    fun `body setter accepts null`() {
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(null)
            .build()
        assertNull(resp.body)
    }

    @Test
    fun `build throws when request missing`() {
        val ex = assertFailsWith<IllegalStateException> {
            Response.builder().protocol(Protocol.HTTP_1_1).status(Status.OK).build()
        }
        assertEquals("request is required", ex.message)
    }

    @Test
    fun `build throws when protocol missing`() {
        val ex = assertFailsWith<IllegalStateException> {
            Response.builder().request(request()).status(Status.OK).build()
        }
        assertEquals("protocol is required", ex.message)
    }

    @Test
    fun `build throws when status missing`() {
        val ex = assertFailsWith<IllegalStateException> {
            Response.builder().request(request()).protocol(Protocol.HTTP_1_1).build()
        }
        assertEquals("status is required", ex.message)
    }

    @Test
    fun `close delegates to the body`() {
        val closed = AtomicInteger(0)
        val body = object : ResponseBody() {
            override fun mediaType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = throw UnsupportedOperationException()
            override fun close() {
                closed.incrementAndGet()
            }
        }
        val resp = Response.builder()
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
        val resp = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .build()
        // Should not throw.
        resp.close()
        assertNull(resp.body)
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
        val body = object : ResponseBody() {
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
}
