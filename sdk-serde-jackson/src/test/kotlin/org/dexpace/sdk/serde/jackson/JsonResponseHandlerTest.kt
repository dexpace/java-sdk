/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.type.TypeReference
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.ParsedResponse
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.SerdeException
import org.dexpace.sdk.io.OkioIoProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonResponseHandlerTest {
    data class Dto(val name: String, val score: Int)

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun jsonResponse(payload: String): Response {
        val source = Io.provider.source(payload.toByteArray())
        val body = ResponseBody.create(source, MediaType.parse("application/json"), payload.length.toLong())
        return Response.builder()
            .request(Request.builder().url("https://api.example.test/p").method(Method.GET).build())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(body)
            .build()
    }

    // A body that tracks close() so consume-once can be asserted.
    private class CountingBody(payload: String) : ResponseBody() {
        val closeCount = AtomicInteger(0)
        private val source = Io.provider.source(payload.toByteArray())

        override fun mediaType(): MediaType = MediaType.parse("application/json")

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = source

        override fun close() {
            closeCount.incrementAndGet()
            source.close()
        }
    }

    @Test
    fun `jsonHandler deserializes a JSON body into the target type`() {
        val serde = JacksonSerde.withDefaults()
        val handler = jsonHandler(serde, Dto::class.java)
        val dto = handler.handle(jsonResponse("""{"name":"a","score":7}"""))
        assertEquals(Dto("a", 7), dto)
    }

    @Test
    fun `jsonHandler closes the response after parsing`() {
        val serde = JacksonSerde.withDefaults()
        val body = CountingBody("""{"name":"a","score":7}""")
        val resp =
            Response.builder()
                .request(Request.builder().url("https://api.example.test/p").method(Method.GET).build())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(body)
                .build()
        jsonHandler(serde, Dto::class.java).handle(resp)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun `jsonHandler surfaces malformed JSON as a SerdeException`() {
        val serde = JacksonSerde.withDefaults()
        val handler = jsonHandler(serde, Dto::class.java)
        val ex = assertFailsWith<SerdeException> { handler.handle(jsonResponse("not json at all")) }
        // The original Jackson failure is preserved as the cause.
        assertEquals(true, ex.cause != null)
    }

    @Test
    fun `jsonHandler reports a missing body as a SerdeException`() {
        val serde = JacksonSerde.withDefaults()
        val resp =
            Response.builder()
                .request(Request.builder().url("https://api.example.test/p").method(Method.GET).build())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.NO_CONTENT)
                .build()
        assertFailsWith<SerdeException> { jsonHandler(serde, Dto::class.java).handle(resp) }
    }

    @Test
    fun `jsonHandler with a TypeReference parses parametric types`() {
        val serde = JacksonSerde.withDefaults()
        val handler = jsonHandler(serde, object : TypeReference<List<Dto>>() {})
        val list = handler.handle(jsonResponse("""[{"name":"a","score":1},{"name":"b","score":2}]"""))
        assertEquals(listOf(Dto("a", 1), Dto("b", 2)), list)
    }

    @Test
    fun `jsonHandler composes with ParsedResponse for lazy parse-once`() {
        val serde = JacksonSerde.withDefaults()
        val body = CountingBody("""{"name":"a","score":7}""")
        val resp =
            Response.builder()
                .request(Request.builder().url("https://api.example.test/p").method(Method.GET).build())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(body)
                .build()
        val parsed = ParsedResponse.of(resp, jsonHandler(serde, Dto::class.java))

        // Header access does not parse / consume.
        assertEquals(Status.OK, parsed.status)
        assertEquals(0, body.closeCount.get())

        assertEquals(Dto("a", 7), parsed.value())
        assertEquals(Dto("a", 7), parsed.value())
        assertEquals(1, body.closeCount.get())
    }
}
