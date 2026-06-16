/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResponseHandlerTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.test/p")
            .method(Method.GET)
            .build()

    private fun response(
        payload: String = "abc",
        status: Status = Status.OK,
        mediaType: MediaType? = null,
        body: ResponseBody? = stringBody(payload, mediaType),
    ): Response =
        Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(status)
            .message("OK")
            .addHeader("X-Trace", "t-1")
            .let { if (body != null) it.body(body) else it }
            .build()

    private fun stringBody(
        payload: String,
        mediaType: MediaType? = null,
    ): ResponseBody {
        val source = Io.provider.source(payload.toByteArray())
        return ResponseBody.create(source, mediaType, payload.length.toLong())
    }

    // A body that records how many times it was closed and exposes a single-use source.
    private class CountingBody(payload: String) : ResponseBody() {
        val closeCount = AtomicInteger(0)
        private val source = Io.provider.source(payload.toByteArray())

        override fun mediaType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = source

        override fun close() {
            closeCount.incrementAndGet()
            source.close()
        }
    }

    // ---- ResponseHandler.string() ----

    @Test
    fun `string handler reads the body as UTF-8`() {
        val handled = ResponseHandler.string().handle(response("hello world"))
        assertEquals("hello world", handled)
    }

    @Test
    fun `string handler closes the response after reading`() {
        val body = CountingBody("payload")
        val resp = response(body = body)
        ResponseHandler.string().handle(resp)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun `string handler returns empty string for a bodyless response`() {
        val resp = response(body = null)
        assertEquals("", ResponseHandler.string().handle(resp))
    }

    // ---- ResponseHandler.empty() ----

    @Test
    fun `empty handler drains and closes the body returning Unit`() {
        val body = CountingBody("ignored payload")
        val resp = response(body = body)
        val result: Unit = ResponseHandler.empty().handle(resp)
        assertEquals(Unit, result)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun `empty handler tolerates a bodyless response`() {
        ResponseHandler.empty().handle(response(body = null))
    }

    // ---- ParsedResponse: raw access without parsing ----

    @Test
    fun `raw status headers and metadata are readable without invoking the handler`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String> {
                calls.incrementAndGet()
                "v"
            }
        val parsed = ParsedResponse.of(response("body", status = Status.CREATED), handler)

        assertEquals(Status.CREATED, parsed.status)
        assertEquals(Protocol.HTTP_1_1, parsed.protocol)
        assertEquals("OK", parsed.message)
        assertEquals(listOf("t-1"), parsed.headers.values("X-Trace"))
        assertSame(parsed.raw.request, parsed.request)
        // The handler must not have run merely from reading headers/status.
        assertEquals(0, calls.get())
    }

    // ---- ParsedResponse: lazy + parse-once ----

    @Test
    fun `value is not parsed until first access`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String> {
                calls.incrementAndGet()
                "parsed"
            }
        ParsedResponse.of(response(), handler)
        assertEquals(0, calls.get())
    }

    @Test
    fun `value parses exactly once across repeated access`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String> {
                calls.incrementAndGet()
                "parsed"
            }
        val parsed = ParsedResponse.of(response(), handler)

        assertEquals("parsed", parsed.value())
        assertEquals("parsed", parsed.value())
        assertEquals("parsed", parsed.value())
        assertEquals(1, calls.get())
    }

    @Test
    fun `value memoizes a null result without re-parsing`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String?> {
                calls.incrementAndGet()
                null
            }
        val parsed = ParsedResponse.of(response(), handler)

        assertNull(parsed.value())
        assertNull(parsed.value())
        assertEquals(1, calls.get())
    }

    @Test
    fun `parse-once consumes the underlying body exactly once`() {
        val body = CountingBody("once")
        val parsed = ParsedResponse.of(response(body = body), ResponseHandler.string())

        assertEquals("once", parsed.value())
        assertEquals("once", parsed.value())
        // The string handler closes the body when it parses; parse-once means only one close.
        assertEquals(1, body.closeCount.get())
    }

    // ---- ParsedResponse: error semantics ----

    @Test
    fun `a handler failure is cached and rethrown without re-parsing`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String> {
                calls.incrementAndGet()
                throw IOException("boom")
            }
        val parsed = ParsedResponse.of(response(), handler)

        val first = assertFailsWith<IOException> { parsed.value() }
        val second = assertFailsWith<IOException> { parsed.value() }
        assertEquals("boom", first.message)
        assertSame(first, second)
        assertEquals(1, calls.get())
    }

    // ---- ParsedResponse: Closeable ----

    @Test
    fun `close forwards to the raw response body`() {
        val body = CountingBody("x")
        val parsed = ParsedResponse.of(response(body = body), ResponseHandler.empty())
        parsed.close()
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun `parsedWith extension builds a ParsedResponse bound to the handler`() {
        val parsed = response("ext").parsedWith(ResponseHandler.string())
        assertEquals("ext", parsed.value())
    }

    // ---- ParsedResponse: concurrency ----

    @Test
    fun `concurrent first access parses exactly once`() {
        val calls = AtomicInteger(0)
        val handler =
            ResponseHandler<String> {
                calls.incrementAndGet()
                Thread.sleep(20)
                "parsed"
            }
        val parsed = ParsedResponse.of(response(), handler)

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    try {
                        assertEquals("parsed", parsed.value())
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, calls.get())
    }
}
