/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpResponseExceptionTest {
    @Test
    fun `null response and null cause means not retryable`() {
        val ex = HttpResponseException("boom", response = null)
        assertFalse(ex.isRetryable)
        assertNull(ex.response)
        assertNull(ex.cause)
        assertNull(ex.value)
    }

    @Test
    fun `200 OK response is not retryable`() {
        val ex = HttpResponseException("ok-but-thrown", response = response(Status.OK))
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `503 Service Unavailable response is retryable`() {
        val ex = HttpResponseException("unavailable", response = response(Status.SERVICE_UNAVAILABLE))
        assertTrue(ex.isRetryable)
    }

    @Test
    fun `501 Not Implemented response is NOT retryable`() {
        // 501 is explicitly excluded from the retryable 5xx range.
        val ex = HttpResponseException("not impl", response = response(Status.NOT_IMPLEMENTED))
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `505 HTTP Version Not Supported response is NOT retryable`() {
        val ex = HttpResponseException("bad version", response = response(Status.HTTP_VERSION_NOT_SUPPORTED))
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `cause is IOException with null response is retryable`() {
        val cause = IOException("network")
        val ex = HttpResponseException("io failed", response = null, cause = cause)
        assertTrue(ex.isRetryable)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `cause chain wraps IOException is retryable`() {
        val root = IOException("root")
        val middle = RuntimeException("middle", root)
        val outer = IllegalStateException("outer", middle)
        val ex = HttpResponseException("nested", response = null, cause = outer)
        assertTrue(ex.isRetryable)
    }

    @Test
    fun `response takes precedence over cause when response status is non-retryable`() {
        // Response is 200 (not retryable) but cause is IOException (would be retryable).
        // Response wins because it is non-null — matches the spec's response-first rule.
        val ex =
            HttpResponseException(
                "ok with io cause",
                response = response(Status.OK),
                cause = IOException("io"),
            )
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `self-referential cause chain does not infinite-loop`() {
        val selfCausing = SelfReferentialException("loop")
        selfCausing.setSelfCause()
        // Construction must terminate; isRetryable must be false (no IOException in the chain).
        val ex = HttpResponseException("self loop", response = null, cause = selfCausing)
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `value parameter is preserved`() {
        val payload = mapOf("error" to "rate-limited")
        val ex =
            HttpResponseException(
                "payload",
                response = response(Status.TOO_MANY_REQUESTS),
                value = payload,
            )
        assertSame(payload, ex.value)
        assertTrue(ex.isRetryable) // 429 is retryable.
    }

    @Test
    fun `extends IOException`() {
        val ex = HttpResponseException("io", response = null)
        // Catchable by IOException — the whole point of inheriting from it.
        val asIo: IOException = ex
        assertEquals("io", asIo.message)
    }

    @Test
    fun `message and cause are propagated to IOException constructor`() {
        val cause = RuntimeException("root")
        val ex = HttpResponseException("oops", response = null, cause = cause)
        assertEquals("oops", ex.message)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `subclasses can be defined and inherit isRetryable behavior`() {
        class TypedHttpResponseException(message: String, response: Response?, cause: Throwable?) :
            HttpResponseException(message, response, value = null, cause = cause)

        val ex =
            TypedHttpResponseException(
                "typed",
                response = response(Status.SERVICE_UNAVAILABLE),
                cause = null,
            )
        assertTrue(ex.isRetryable)
    }

    // ---- Helpers -----------------------------------------------------------------------

    private fun response(status: Status): Response {
        val req =
            Request.builder()
                .method(Method.GET)
                .url("https://api.example.com/")
                .build()
        return Response.builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .status(status)
            .build()
    }

    private class SelfReferentialException(message: String) : RuntimeException(message) {
        private var selfCause: Boolean = false

        fun setSelfCause() {
            selfCause = true
        }

        override val cause: Throwable?
            get() = if (selfCause) this else null
    }
}
