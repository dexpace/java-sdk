/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.steps.DefaultAsyncInstrumentationStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultAsyncRetryStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultInstrumentationStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRedirectStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.io.OkioIoProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StandardResilienceTest {
    private lateinit var scheduler: ScheduledExecutorService

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
        scheduler = Executors.newSingleThreadScheduledExecutor()
    }

    @AfterTest
    fun tearDown() {
        scheduler.shutdownNow()
    }

    @Test
    fun `HttpPipeline standard wires redirect retry and instrumentation pillars`() {
        val pipeline = HttpPipeline.standard(FakeHttpClient())

        assertEquals(3, pipeline.steps.size)
        assertTrue(pipeline.steps.any { it is DefaultRedirectStep }, "redirect pillar present")
        assertTrue(pipeline.steps.any { it is DefaultRetryStep }, "retry pillar present")
        assertTrue(pipeline.steps.any { it is DefaultInstrumentationStep }, "instrumentation pillar present")
    }

    @Test
    fun `HttpPipeline standard retries a retryable 503 then succeeds`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(503) }
                .enqueue { status(200).body("ok") }

        val response = HttpPipeline.standard(fake).send(getRequest())

        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
        response.close()
    }

    @Test
    fun `appendStandardResilience preserves the pillars' stage order`() {
        val pipeline = HttpPipelineBuilder(FakeHttpClient()).appendStandardResilience().build()

        // Stage order: REDIRECT (100) < RETRY (200) < LOGGING (700).
        assertTrue(pipeline.steps[0] is DefaultRedirectStep)
        assertTrue(pipeline.steps[1] is DefaultRetryStep)
        assertTrue(pipeline.steps[2] is DefaultInstrumentationStep)
    }

    @Test
    fun `AsyncHttpPipeline standard wires retry and instrumentation but no redirect`() {
        val client =
            AsyncHttpClient { request ->
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.OK)
                        .build(),
                )
            }

        val pipeline = AsyncHttpPipeline.standard(client, scheduler)

        assertEquals(2, pipeline.steps.size)
        assertTrue(pipeline.steps.any { it is DefaultAsyncRetryStep }, "async retry pillar present")
        assertTrue(pipeline.steps.any { it is DefaultAsyncInstrumentationStep }, "async instrumentation pillar present")
    }

    @Test
    fun `appendStandardResilience over an occupied retry pillar throws naming RETRY without mutating`() {
        val preexistingRetry = DefaultRetryStep()
        val builder = HttpPipelineBuilder(FakeHttpClient()).append(preexistingRetry)

        val ex = assertFailsWith<IllegalStateException> { builder.appendStandardResilience() }
        assertTrue("RETRY" in ex.message.orEmpty(), "message names the RETRY pillar: ${ex.message}")

        // Atomic all-or-nothing: the redirect/instrumentation pillars were never installed, and the
        // pre-existing retry step is left exactly as it was.
        val steps = builder.build().steps
        assertEquals(1, steps.size, "builder is unchanged after the rejected call")
        assertSame(preexistingRetry, steps.single(), "the original retry step survives untouched")
        assertFalse(steps.any { it is DefaultRedirectStep }, "no redirect pillar was partially installed")
    }

    @Test
    fun `appendStandardResilience called twice throws on the second call`() {
        val builder = HttpPipelineBuilder(FakeHttpClient()).appendStandardResilience()

        val ex = assertFailsWith<IllegalStateException> { builder.appendStandardResilience() }
        // Every standard pillar is occupied on the second call; the message enumerates them.
        assertTrue("REDIRECT" in ex.message.orEmpty(), ex.message)
        assertTrue("RETRY" in ex.message.orEmpty(), ex.message)
        assertTrue("LOGGING" in ex.message.orEmpty(), ex.message)

        // Still exactly the first standard stack — the failed second call added nothing.
        assertEquals(3, builder.build().steps.size)
    }

    @Test
    fun `appendStandardResilience on a fresh builder installs all three pillars`() {
        val steps = HttpPipelineBuilder(FakeHttpClient()).appendStandardResilience().build().steps

        assertEquals(3, steps.size)
        assertTrue(steps.any { it is DefaultRedirectStep })
        assertTrue(steps.any { it is DefaultRetryStep })
        assertTrue(steps.any { it is DefaultInstrumentationStep })
    }

    @Test
    fun `async appendStandardResilience over an occupied retry pillar throws naming RETRY without mutating`() {
        val client = okAsyncClient()
        val preexistingRetry = DefaultAsyncRetryStep(scheduler)
        val builder = AsyncHttpPipelineBuilder(client).append(preexistingRetry)

        val ex = assertFailsWith<IllegalStateException> { builder.appendStandardResilience(scheduler) }
        assertTrue("RETRY" in ex.message.orEmpty(), "message names the RETRY pillar: ${ex.message}")

        val steps = builder.build().steps
        assertEquals(1, steps.size, "builder is unchanged after the rejected call")
        assertSame(preexistingRetry, steps.single(), "the original retry step survives untouched")
        assertFalse(
            steps.any { it is DefaultAsyncInstrumentationStep },
            "no instrumentation pillar was partially installed",
        )
    }

    @Test
    fun `async appendStandardResilience called twice throws on the second call`() {
        val builder = AsyncHttpPipelineBuilder(okAsyncClient()).appendStandardResilience(scheduler)

        val ex = assertFailsWith<IllegalStateException> { builder.appendStandardResilience(scheduler) }
        assertTrue("RETRY" in ex.message.orEmpty(), ex.message)
        assertTrue("LOGGING" in ex.message.orEmpty(), ex.message)

        assertEquals(2, builder.build().steps.size)
    }

    private fun okAsyncClient(): AsyncHttpClient =
        AsyncHttpClient { request ->
            CompletableFuture.completedFuture(
                Response.builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .status(Status.OK)
                    .build(),
            )
        }

    private fun getRequest(): Request = Request.builder().method(Method.GET).url("https://api.example.com/").build()
}
