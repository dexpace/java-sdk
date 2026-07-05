/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.Serializer
import org.dexpace.sdk.io.OkioIoProvider
import java.io.InputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResponseExtensionsTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.test/")
            .method(Method.GET)
            .build()

    private fun response(
        status: Status,
        bodyText: String? = null,
    ): Response {
        val builder =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(status)
        if (bodyText != null) {
            val source = Io.provider.source(bodyText.toByteArray(Charsets.UTF_8))
            builder.body(ResponseBody.create(source))
        }
        return builder.build()
    }

    // ---- throwOnError (T3) -----------------------------------------------------------

    @Test
    fun `throwOnError returns same instance for 2xx`() {
        val r = response(Status.OK, "ok body")
        val returned = r.throwOnError()
        assertSame(r, returned)
    }

    @Test
    fun `throwOnError returns same instance for 204 No Content`() {
        val r = response(Status.NO_CONTENT)
        val returned = r.throwOnError()
        assertSame(r, returned)
    }

    @Test
    fun `throwOnError returns same instance for 304 Not Modified`() {
        val r = response(Status.fromCode(304))
        val returned = r.throwOnError()
        assertSame(r, returned)
    }

    @Test
    fun `throwOnError returns 3xx unchanged without consuming the body`() {
        val r = response(Status.fromCode(302), "redirect target body")
        val returned = r.throwOnError()
        assertSame(r, returned)
        // The body must be intact — throwOnError must not drain a non-error response.
        assertEquals("redirect target body", assertNotNull(returned.body).string())
    }

    @Test
    fun `throwOnError returns same instance for 1xx informational`() {
        val r = response(Status.fromCode(100))
        assertSame(r, r.throwOnError())
    }

    @Test
    fun `throwOnError throws HttpException for 400`() {
        val r = response(Status.BAD_REQUEST, """{"error":"bad"}""")
        assertFailsWith<HttpException> {
            r.throwOnError()
        }
    }

    @Test
    fun `throwOnError throws HttpException for 404`() {
        val r = response(Status.NOT_FOUND, """{"error":"not found"}""")
        assertFailsWith<HttpException> {
            r.throwOnError()
        }
    }

    @Test
    fun `throwOnError throws HttpException for 500`() {
        val r = response(Status.INTERNAL_SERVER_ERROR, "boom")
        assertFailsWith<HttpException> {
            r.throwOnError()
        }
    }

    @Test
    fun `throwOnError exception body is still readable after throw`() {
        val bodyText = """{"error":"rate limited"}"""
        val r = response(Status.TOO_MANY_REQUESTS, bodyText)
        val ex =
            assertFailsWith<HttpException> {
                r.throwOnError()
            }
        val exBody = assertNotNull(ex.body)
        val bodyString = exBody.string()
        assertEquals(bodyText, bodyString)
    }

    @Test
    fun `throwOnError exception status matches original response status`() {
        val r = response(Status.NOT_FOUND, "nope")
        val ex =
            assertFailsWith<HttpException> {
                r.throwOnError()
            }
        assertEquals(Status.NOT_FOUND, ex.status)
    }

    @Test
    fun `throwOnError works when response body is null`() {
        val r = response(Status.INTERNAL_SERVER_ERROR, null)
        val ex =
            assertFailsWith<HttpException> {
                r.throwOnError()
            }
        assertEquals(Status.INTERNAL_SERVER_ERROR, ex.status)
    }

    // ---- throwOnError truncation -------------------------------------------------

    @Test
    fun `throwOnError truncates body larger than 1 MiB`() {
        val oneMib = 1 * 1024 * 1024
        val oversizedBody = ByteArray(oneMib + 512) { 'x'.code.toByte() }
        val source = Io.provider.source(oversizedBody)
        val r =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.BAD_REQUEST)
                .body(ResponseBody.create(source))
                .build()
        val ex = assertFailsWith<HttpException> { r.throwOnError() }
        val exBody = assertNotNull(ex.body)
        val bytes = exBody.bytes()
        assertEquals(oneMib, bytes.size)
    }

    // ---- HttpException.bodyAs (T3) ---------------------------------------------------

    data class ErrorPayload(val code: String)

    @Test
    fun `bodyAs decodes body with Deserializer`() {
        val expected = ErrorPayload("err_code")
        val fakeDeserializer = constantDeserializer(expected)
        val r = response(Status.BAD_REQUEST, """{"code":"err_code"}""")
        val ex = assertFailsWith<HttpException> { r.throwOnError() }
        val result = ex.bodyAs(fakeDeserializer, ErrorPayload::class.java)
        assertEquals(expected, result)
    }

    @Test
    fun `bodyAs returns null when exception body is null`() {
        val neverDeserializer = throwingDeserializer()
        val ex =
            object : HttpException(
                status = Status.BAD_REQUEST,
                headers = Headers.Builder().build(),
                body = null,
            ) {}
        assertNull(ex.bodyAs(neverDeserializer, ErrorPayload::class.java))
    }

    @Test
    fun `bodyAs returns null when Deserializer throws`() {
        val r = response(Status.INTERNAL_SERVER_ERROR, """{"x":1}""")
        val ex = assertFailsWith<HttpException> { r.throwOnError() }
        assertNull(ex.bodyAs(throwingDeserializer(), ErrorPayload::class.java))
    }

    @Test
    fun `bodyAs closes the body on success`() {
        var closed = false
        val fakeBody = trackingBody { closed = true }
        val ex =
            object : HttpException(
                status = Status.BAD_REQUEST,
                headers = Headers.Builder().build(),
                body = fakeBody,
            ) {}
        ex.bodyAs(constantDeserializer("ok"), String::class.java)
        assertTrue(closed, "ResponseBody.close() must be called after successful decode")
    }

    @Test
    fun `bodyAs closes the body when Deserializer throws`() {
        var closed = false
        val fakeBody = trackingBody { closed = true }
        val ex =
            object : HttpException(
                status = Status.INTERNAL_SERVER_ERROR,
                headers = Headers.Builder().build(),
                body = fakeBody,
            ) {}
        ex.bodyAs(throwingDeserializer(), String::class.java)
        assertTrue(closed, "ResponseBody.close() must be called even when the deserializer throws")
    }

    // ---- Response.deserialize (T9) ---------------------------------------------------

    data class User(val id: Int)

    @Test
    fun `deserialize returns decoded value`() {
        val expected = User(42)
        val serde = fakeSerde(constantDeserializer(expected))
        val r = response(Status.OK, """{"id":42}""")
        val result = r.deserialize(serde, User::class.java)
        assertEquals(expected, result)
    }

    @Test
    fun `deserialize throws IllegalStateException when body is null`() {
        val serde = fakeSerde(throwingDeserializer())
        val r = response(Status.OK, null)
        assertFailsWith<IllegalStateException> {
            r.deserialize(serde, User::class.java)
        }
    }

    @Test
    fun `deserialize reified delegates to class overload`() {
        val expected = User(99)
        val serde = fakeSerde(constantDeserializer(expected))
        val r = response(Status.OK, """{"id":99}""")
        val result = r.deserialize<User>(serde)
        assertEquals(expected, result)
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Returns a [ResponseBody] backed by an empty in-memory source; [onClose] is invoked when closed. */
    private fun trackingBody(onClose: () -> Unit): ResponseBody {
        val source = Io.provider.source(ByteArray(0))
        return object : ResponseBody() {
            override fun mediaType(): MediaType? = null

            override fun contentLength(): Long = 0L

            override fun source(): BufferedSource = source

            override fun close() {
                onClose()
                source.close()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> constantDeserializer(value: T): Deserializer =
        object : Deserializer {
            override fun <R> deserialize(
                input: String,
                type: Class<R>,
            ): R = throw UnsupportedOperationException()

            override fun <R> deserialize(
                input: ByteArray,
                type: Class<R>,
            ): R = throw UnsupportedOperationException()

            override fun <R> deserialize(
                inputStream: InputStream,
                type: Class<R>,
            ): R = value as R
        }

    private fun throwingDeserializer(): Deserializer =
        object : Deserializer {
            override fun <R> deserialize(
                input: String,
                type: Class<R>,
            ): R = throw RuntimeException("decode failure")

            override fun <R> deserialize(
                input: ByteArray,
                type: Class<R>,
            ): R = throw RuntimeException("decode failure")

            override fun <R> deserialize(
                inputStream: InputStream,
                type: Class<R>,
            ): R =
                throw RuntimeException(
                    "decode failure",
                )
        }

    private fun fakeSerde(deserializer: Deserializer): Serde =
        object : Serde {
            override val serializer: Serializer get() = throw UnsupportedOperationException()
            override val deserializer: Deserializer = deserializer

            override fun contentType() = MediaType.APPLICATION_JSON
        }
}
