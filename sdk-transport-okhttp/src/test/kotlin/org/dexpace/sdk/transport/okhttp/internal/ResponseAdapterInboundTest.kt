/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.net.URL
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.Request as OkRequest
import org.dexpace.sdk.core.http.request.Request as SdkRequest

/**
 * Verifies that [ResponseAdapter] drops a malformed inbound (response) header rather than letting
 * the model layer's outbound validation fail the whole response. OkHttp parses response headers
 * leniently and can surface a control byte (notably over HTTP/2); the adapter must catch the
 * model-layer rejection per header and keep the rest of the response intact.
 */
class ResponseAdapterInboundTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `adapt drops a malformed inbound response header instead of failing the response`() {
        // DEL (0x7F) is rejected by the model layer yet allowed past OkHttp's lenient
        // addUnsafeNonAscii, standing in for a control byte a server delivers over the wire.
        val del = 127.toChar()
        val headers =
            Headers.Builder()
                .add("X-Good", "fine")
                .addUnsafeNonAscii("X-Bad", "a" + del + "b")
                .build()
        val okResponse =
            Response.Builder()
                .request(OkRequest.Builder().url("http://example.test/inbound").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .headers(headers)
                .body("ok".toResponseBody(null))
                .build()
        val adapter = ResponseAdapter(ClientLogger("test.ResponseAdapterInboundTest"))

        val sdkResponse = adapter.adapt(simpleRequest(), okResponse)

        sdkResponse.use {
            assertEquals("fine", it.headers.get("X-Good"), "well-formed inbound header must survive")
            assertNull(it.headers.get("X-Bad"), "malformed inbound header must be dropped, not throw")
        }
    }

    private fun simpleRequest(): SdkRequest =
        SdkRequest.builder()
            .method(Method.GET)
            .url(URL("http://example.test/inbound"))
            .build()
}
