/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.NotFoundException
import org.dexpace.sdk.core.http.response.exception.Retryable
import org.dexpace.sdk.core.http.response.exception.ServiceUnavailableException
import org.dexpace.sdk.core.http.response.exception.TooManyRequestsException
import org.dexpace.sdk.core.pipeline.ResponseOutcome
import org.dexpace.sdk.core.pipeline.ResponsePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies that [ThrowOnHttpErrorStep] is the live error path: an error response is routed
 * through [org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory.fromResponse] and
 * surfaces as the matching typed [HttpException], while a success response passes through.
 */
class ThrowOnHttpErrorStepTest {
    // ---- the step in isolation ----------------------------------------------------------

    @Test
    fun `2xx response passes through unchanged`() {
        val ok = response(Status.OK)
        val out = ThrowOnHttpErrorStep.execute(ok, ctx())
        assertSame(ok, out, "a success response must pass through untouched")
    }

    @Test
    fun `3xx response passes through unchanged`() {
        // The factory rejects non-4xx/5xx; the step must not even call it for a 3xx.
        val redirect = response(Status.MOVED_PERMANENTLY)
        assertSame(redirect, ThrowOnHttpErrorStep.execute(redirect, ctx()))
    }

    @Test
    fun `404 maps to NotFoundException via the factory`() {
        val ex =
            assertFailsWith<HttpException> {
                ThrowOnHttpErrorStep.execute(response(Status.NOT_FOUND), ctx())
            }
        assertIs<NotFoundException>(ex, "404 must produce the factory's NotFoundException")
        assertEquals(Status.NOT_FOUND, ex.status)
    }

    @Test
    fun `503 maps to a retryable ServiceUnavailableException`() {
        val ex =
            assertFailsWith<HttpException> {
                ThrowOnHttpErrorStep.execute(response(Status.SERVICE_UNAVAILABLE), ctx())
            }
        assertIs<ServiceUnavailableException>(ex)
        assertTrue(ex.isRetryable, "503 must surface as retryable")
    }

    @Test
    fun `error exception carries the response headers and status`() {
        val headers = Headers.Builder().add("Retry-After", "7").build()
        val ex =
            assertFailsWith<HttpException> {
                ThrowOnHttpErrorStep.execute(response(Status.TOO_MANY_REQUESTS, headers), ctx())
            }
        assertIs<TooManyRequestsException>(ex)
        assertEquals("7", ex.headers.get("Retry-After"))
        assertEquals(Status.TOO_MANY_REQUESTS, ex.status)
    }

    // ---- wired through the recovery-aware ResponsePipeline ------------------------------

    @Test
    fun `pipeline turns a 500 success-outcome into a Failure carrying the factory exception`() {
        val pipeline = ResponsePipeline(responseSteps = listOf(ThrowOnHttpErrorStep))
        val outcome = ResponseOutcome.Success(response(Status.INTERNAL_SERVER_ERROR))

        val result = pipeline.apply(outcome, ctx())

        val failure = assertIs<ResponseOutcome.Failure>(result, "a 5xx must become a Failure")
        val error = assertIs<HttpException>(failure.error)
        assertEquals(Status.INTERNAL_SERVER_ERROR, error.status)
    }

    @Test
    fun `pipeline leaves a 2xx success-outcome as a Success`() {
        val pipeline = ResponsePipeline(responseSteps = listOf(ThrowOnHttpErrorStep))
        val ok = response(Status.OK)

        val result = pipeline.apply(ResponseOutcome.Success(ok), ctx())

        val success = assertIs<ResponseOutcome.Success>(result)
        assertSame(ok, success.response)
    }

    @Test
    fun `the mapped failure is Retryable so the retry classifier can key off it`() {
        // End-to-end check of the #23 / #37 seam: the error path emits an HttpException, and
        // that exception is Retryable, which is exactly what RetryStep.isClassifiedRetryable
        // matches on.
        val pipeline = ResponsePipeline(responseSteps = listOf(ThrowOnHttpErrorStep))
        val result = pipeline.apply(ResponseOutcome.Success(response(Status.BAD_GATEWAY)), ctx())

        val failure = assertIs<ResponseOutcome.Failure>(result)
        val retryable = assertIs<Retryable>(failure.error)
        assertTrue(retryable.isRetryable, "502 must classify as retryable through the interface")
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun ctx(): DispatchContext = DispatchContext.default()

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.GET)
            .build()

    private fun response(
        status: Status,
        headers: Headers = Headers.Builder().build(),
    ): Response =
        Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(status)
            .headers(headers)
            .build()
}
