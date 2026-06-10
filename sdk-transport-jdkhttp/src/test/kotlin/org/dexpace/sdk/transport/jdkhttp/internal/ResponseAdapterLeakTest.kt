/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSession
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.dexpace.sdk.core.http.request.Request as SdkRequest

/**
 * Verifies that [ResponseAdapter.adapt] never leaks the response [InputStream] when adaptation
 * throws after the body has been acquired — the close-on-throw guard required for parity with
 * the OkHttp transport. Both the synchronous `execute` and the asynchronous
 * `sendAsync().thenApply` paths route through `adapt`, so closing in `adapt` covers both.
 */
class ResponseAdapterLeakTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `adapt closes the response body when adaptation throws`() {
        val closed = AtomicBoolean(false)
        val body = TrackingInputStream(ByteArray(0), closed)
        // version() throws inside the adaptation try-block (after body() is acquired), forcing
        // the catch path. This stands in for any real post-body failure (provider missing, OOM
        // while wrapping, builder rejection) without relying on global IoProvider state.
        val response = ThrowingVersionResponse(code = 200, bodyStream = body)
        val adapter = ResponseAdapter()

        val ex =
            assertFails {
                adapter.adapt(simpleRequest(), response)
            }

        assertTrue(ex is IllegalStateException, "the injected failure must propagate, got ${ex::class}")
        assertTrue(closed.get(), "response InputStream must be closed when adaptation throws")
    }

    @Test
    fun `adapt does not close the body on the success path`() {
        val closed = AtomicBoolean(false)
        val body = TrackingInputStream("payload".toByteArray(), closed)
        val response = OkResponse(code = 200, bodyStream = body)
        val adapter = ResponseAdapter()

        val sdkResponse = adapter.adapt(simpleRequest(), response)

        // On success the body is owned by the SDK response; it must stay open until the caller
        // closes the SDK response.
        assertTrue(!closed.get(), "body must remain open on the success path")
        sdkResponse.close()
        assertTrue(closed.get(), "closing the SDK response must cascade-close the body")
    }

    private fun simpleRequest(): SdkRequest =
        SdkRequest.builder()
            .method(Method.GET)
            .url(URL("http://example.test/leak"))
            .build()

    /** [InputStream] that flips [closed] on `close()`, so a leak shows up as `closed == false`. */
    private class TrackingInputStream(
        bytes: ByteArray,
        private val closed: AtomicBoolean,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len)

        override fun close() {
            closed.set(true)
            delegate.close()
        }
    }

    /** Minimal [HttpResponse] whose `version()` throws, to drive the adaptation catch path. */
    private class ThrowingVersionResponse(
        private val code: Int,
        private val bodyStream: InputStream,
    ) : BaseResponse() {
        override fun statusCode(): Int = code

        override fun body(): InputStream = bodyStream

        override fun version(): HttpClient.Version = throw IllegalStateException("injected version() failure")
    }

    /** Minimal successful [HttpResponse]. */
    private class OkResponse(
        private val code: Int,
        private val bodyStream: InputStream,
    ) : BaseResponse() {
        override fun statusCode(): Int = code

        override fun body(): InputStream = bodyStream

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    /**
     * Shared abstract base supplying inert implementations of the [HttpResponse] members the
     * adapter never consults, so the concrete fakes only override what each test needs.
     */
    private abstract class BaseResponse : HttpResponse<InputStream> {
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap<String, List<String>>()) { _, _ -> true }

        override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create("http://example.test/leak")).build()

        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = URI.create("http://example.test/leak")
    }
}
