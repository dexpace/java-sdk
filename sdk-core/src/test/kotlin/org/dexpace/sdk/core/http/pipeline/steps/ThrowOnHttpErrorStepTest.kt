/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.exception.ClientErrorException
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.NotFoundException
import org.dexpace.sdk.core.http.response.exception.ServerErrorException
import org.dexpace.sdk.core.http.response.exception.ServiceUnavailableException
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ThrowOnHttpErrorStepTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `stage is PRE_REDIRECT`() {
        assertEquals(Stage.PRE_REDIRECT, ThrowOnHttpErrorStep().stage)
    }

    @Test
    fun `404 throws the mapped NotFoundException and the exception body is still readable`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(404).body("{\"error\":\"missing\"}", MediaType.parse("application/json")) }

        val pipeline = HttpPipelineBuilder(fake).throwOnHttpError().build()

        val ex =
            assertFailsWith<NotFoundException> {
                pipeline.send(getRequest("https://api.example.com/thing"))
            }
        assertEquals(404, ex.status.code)
        val body = assertNotNull(ex.body, "mapped exception must carry the error body")
        assertEquals("{\"error\":\"missing\"}", body.source().readUtf8())
    }

    @Test
    fun `500 error body remains readable after the connection is buffered`() {
        val fake = FakeHttpClient().enqueue { status(500).body("boom") }

        val pipeline = HttpPipelineBuilder(fake).throwOnHttpError().build()

        val ex = assertFailsWith<HttpException> { pipeline.send(getRequest("https://api.example.com/x")) }
        assertEquals(500, ex.status.code)
        assertEquals("boom", ex.body!!.source().readUtf8())
    }

    @Test
    fun `error response without a body still maps to the typed exception`() {
        val fake = FakeHttpClient().enqueue { status(404) }

        val pipeline = HttpPipelineBuilder(fake).throwOnHttpError().build()

        val ex =
            assertFailsWith<NotFoundException> {
                pipeline.send(getRequest("https://api.example.com/gone"))
            }
        assertEquals(404, ex.status.code)
    }

    @Test
    fun `non-canonical 4xx and 5xx codes still map to typed exceptions`() {
        // The step now branches on Status.isError directly (a plain 400..599 range check), so a
        // vendor-specific code with no canonical Status constant is still treated as an error and
        // routed through the 4xx / 5xx fallbacks.
        val client4xx = FakeHttpClient().enqueue { status(460) }
        val ex4xx =
            assertFailsWith<ClientErrorException> {
                HttpPipelineBuilder(client4xx).throwOnHttpError().build().send(getRequest("https://api.example.com/a"))
            }
        assertEquals(460, ex4xx.status.code)

        val client5xx = FakeHttpClient().enqueue { status(599) }
        val ex5xx =
            assertFailsWith<ServerErrorException> {
                HttpPipelineBuilder(client5xx).throwOnHttpError().build().send(getRequest("https://api.example.com/b"))
            }
        assertEquals(599, ex5xx.status.code)
    }

    @Test
    fun `a 3xx reaching the step without a redirect step is returned untouched`() {
        // No redirect step is installed, so a bare 3xx reaches ThrowOnHttpErrorStep as the terminal
        // response. It is NOT an error (Status.isError is false), so it passes through verbatim.
        val fake = FakeHttpClient().enqueue { status(304) }

        val pipeline = HttpPipelineBuilder(fake).throwOnHttpError().build()

        val response = pipeline.send(getRequest("https://api.example.com/nm"))
        assertEquals(304, response.status.code)
        response.close()
    }

    @Test
    fun `200 is returned untouched with the body intact and readable`() {
        val fake = FakeHttpClient().enqueue { status(200).body("hello", MediaType.parse("text/plain")) }

        val pipeline = HttpPipelineBuilder(fake).throwOnHttpError().build()

        val response = pipeline.send(getRequest("https://api.example.com/ok"))
        assertEquals(200, response.status.code)
        // Body was never consumed by the step, so it is still fully readable.
        assertEquals("hello", response.body!!.source().readUtf8())
        response.close()
    }

    @Test
    fun `does not throw when a retry step recovers a 503-then-200 sequence`() {
        // throwOnHttpError() is OUTER of the RETRY pillar (PRE_REDIRECT < RETRY), so it only sees
        // the terminal 200 the retry step returns — it must NOT throw on the intermediate 503.
        // If the step were placed inside the retry loop it would throw on the first 503 before the
        // retry could recover.
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200).body("recovered") }

        val pipeline =
            HttpPipelineBuilder(fake)
                .throwOnHttpError()
                // FixedClock advances instantly on sleep(), so backoff does not block the test.
                .append(DefaultRetryStep(clock = FixedClock()))
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/retry"))
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
        assertEquals("recovered", response.body!!.source().readUtf8())
        response.close()
    }

    @Test
    fun `does not throw on an intermediate 3xx redirect hop`() {
        // throwOnHttpError() is OUTER of the REDIRECT pillar, so it sees only the final 200 after
        // the redirect is followed — never the intermediate 301.
        val fake =
            FakeHttpClient()
                .enqueue { status(301).header("Location", "https://api.example.com/v2") }
                .enqueue { status(200).body("final") }

        val pipeline =
            HttpPipelineBuilder(fake)
                .throwOnHttpError()
                .append(DefaultRedirectStep())
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/v1"))
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
        response.close()
    }

    @Test
    fun `still throws when retry exhausts and the terminal response is a 503`() {
        // Retry-safe GET, all attempts fail: retry exhausts and returns the terminal 503, which
        // the outer throwOnHttpError() maps to ServiceUnavailableException.
        val fake =
            FakeHttpClient()
                .enqueue { status(503).body("unavailable") }
                .enqueue { status(503).body("still unavailable") }
                .enqueue { status(503).body("nope") }

        val pipeline =
            HttpPipelineBuilder(fake)
                .throwOnHttpError()
                .append(DefaultRetryStep(clock = FixedClock()))
                .build()

        val ex =
            assertFailsWith<ServiceUnavailableException> {
                pipeline.send(getRequest("https://api.example.com/down"))
            }
        assertEquals(503, ex.status.code)
    }

    private fun getRequest(url: String): Request = Request.builder().method(Method.GET).url(url).build()
}
