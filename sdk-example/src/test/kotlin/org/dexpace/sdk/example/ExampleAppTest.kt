/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.example

import mockwebserver3.MockResponse
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.serde.jackson.JacksonSerde
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test that drives the sample's own wiring end-to-end against an embedded HTTPS
 * [mockwebserver3.MockWebServer]: install the I/O provider, build the full pillar pipeline,
 * serialize a typed request, dispatch it through the OkHttp transport over TLS, and deserialize
 * the typed response.
 *
 * This is the deterministic proof that the assembled toolkit works together — the acceptance
 * criterion behind the example module. It exercises [newTlsServer], [installIoProvider],
 * [buildPipeline], and [createUser] exactly as [main] does.
 */
class ExampleAppTest {
    private lateinit var tls: TlsServer

    @BeforeTest
    fun setUp() {
        installIoProvider()
        tls = newTlsServer()
        tls.server.start()
    }

    @AfterTest
    fun tearDown() {
        tls.server.close()
    }

    @Test
    fun createUserRoundTripsTypedPayloadsThroughTheFullPipeline() {
        tls.server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader(
                    HttpHeaderName.CONTENT_TYPE.toString(),
                    CommonMediaTypes.APPLICATION_JSON.toString(),
                )
                .body("""{"id":7,"name":"Ada Lovelace","email":"ada@example.org"}""")
                .build(),
        )

        val serde = JacksonSerde.withDefaults()

        val user =
            tls.newTransportTrusting().use { transport ->
                val pipeline = buildPipeline(transport)
                createUser(
                    pipeline = pipeline,
                    serde = serde,
                    endpoint = tls.server.url("/v1/users").toUrl(),
                    request = CreateUserRequest(name = "Ada Lovelace", email = "ada@example.org"),
                )
            }

        assertEquals(User(id = 7, name = "Ada Lovelace", email = "ada@example.org"), user)

        val recorded = tls.server.takeRequest()
        // The AUTH pillar step stamped the API key into the request.
        assertEquals("Bearer example-api-key", recorded.headers[HttpHeaderName.AUTHORIZATION.toString()])
        // The body the transport sent is the serialized typed request.
        assertEquals(
            """{"name":"Ada Lovelace","email":"ada@example.org"}""",
            recorded.body?.utf8(),
        )
    }
}
