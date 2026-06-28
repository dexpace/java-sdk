/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.QueryParams
import org.dexpace.sdk.core.http.context.ContextStore
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.RequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OperationParamsTest {
    /** Fully-specified test operation. */
    private class TestOp(
        override val method: Method,
        override val pathTemplate: String,
        private val path: Map<String, String> = emptyMap(),
        private val query: QueryParams = QueryParams.empty(),
        private val hdrs: Headers = Headers.builder().build(),
        private val bdy: RequestBody? = null,
        override val operationName: String? = null,
    ) : OperationParams {
        override fun pathParams(): Map<String, String> = path

        override fun queryParams(): QueryParams = query

        override fun headers(): Headers = hdrs

        override fun body(): RequestBody? = bdy
    }

    /** Minimal operation — exercises every default projection. */
    private class MinimalOp : OperationParams {
        override val method: Method = Method.GET
        override val pathTemplate: String = "/ping"
    }

    private val base = "https://api.example.com"

    @Test
    fun `toRequest builds method and url from base and static path`() {
        val request = TestOp(Method.GET, "/pets").toRequest(base)
        assertEquals(Method.GET, request.method)
        assertEquals("https://api.example.com/pets", request.url.toExternalForm())
    }

    @Test
    fun `toRequest substitutes and percent-encodes path params`() {
        val request = TestOp(Method.GET, "/pets/{petId}", path = mapOf("petId" to "a b/c")).toRequest(base)
        assertEquals("https://api.example.com/pets/a%20b%2Fc", request.url.toExternalForm())
    }

    @Test
    fun `toRequest appends query params in order`() {
        val query = QueryParams.builder().add("tag", "a").add("tag", "b").add("page", "2").build()
        val request = TestOp(Method.GET, "/pets", query = query).toRequest(base)
        assertEquals("https://api.example.com/pets?tag=a&tag=b&page=2", request.url.toExternalForm())
    }

    @Test
    fun `toRequest composes path query and headers`() {
        val request =
            TestOp(
                Method.GET,
                "/pets/{id}",
                path = mapOf("id" to "7"),
                query = QueryParams.builder().add("limit", "10").build(),
                hdrs = Headers.builder().add("Accept", "application/json").build(),
            ).toRequest(base)
        assertEquals("https://api.example.com/pets/7?limit=10", request.url.toExternalForm())
        assertEquals("application/json", request.headers.get("Accept"))
    }

    @Test
    fun `toRequest carries the body and method`() {
        val body = RequestBody.create("{}", CommonMediaTypes.APPLICATION_JSON)
        val request = TestOp(Method.POST, "/pets", bdy = body).toRequest(base)
        assertEquals(Method.POST, request.method)
        assertSame(body, request.body)
    }

    @Test
    fun `toRequest trims a trailing slash on the base before joining`() {
        val request = TestOp(Method.GET, "/pets").toRequest("https://api.example.com/")
        assertEquals("https://api.example.com/pets", request.url.toExternalForm())
    }

    @Test
    fun `toRequest joins a base that already has a path prefix`() {
        val request = TestOp(Method.GET, "/pets").toRequest("https://api.example.com/v1")
        assertEquals("https://api.example.com/v1/pets", request.url.toExternalForm())
    }

    @Test
    fun `toRequest throws when a template variable has no value`() {
        assertFailsWith<IllegalArgumentException> {
            TestOp(Method.GET, "/pets/{petId}").toRequest(base)
        }
    }

    @Test
    fun `toRequest fails fast on a template variable containing a slash rather than emitting it literally`() {
        // A malformed `{a/b}` placeholder is captured whole as the variable name; with no matching
        // path parameter it fails fast instead of leaking an unsubstituted `{a/b}` into the URL.
        assertFailsWith<IllegalArgumentException> {
            TestOp(Method.GET, "/pets/{a/b}").toRequest(base)
        }
    }

    @Test
    fun `minimal operation uses empty defaults`() {
        val op = MinimalOp()
        assertTrue(op.pathParams().isEmpty())
        assertTrue(op.queryParams().isEmpty())
        assertTrue(op.headers().names().isEmpty())
        assertNull(op.body())
        assertNull(op.operationName)
        assertEquals("https://api.example.com/ping", op.toRequest(base).url.toExternalForm())
    }

    @Test
    fun `toRequestContext feeds the request into the context chain`() {
        val dispatch = DispatchContext.default()
        val op = TestOp(Method.GET, "/pets")
        val ctx = op.toRequestContext(base, dispatch)
        try {
            assertEquals(op.toRequest(base), ctx.request)
            assertEquals(dispatch.callKey, ctx.callKey)
            assertSame(dispatch.instrumentationContext, ctx.instrumentationContext)
            assertSame(ctx, ContextStore.get(ctx.callKey))
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `toRequestContext carries the operation name onto the context for the tracing seam`() {
        val dispatch = DispatchContext.default()
        val op = TestOp(Method.GET, "/pets", operationName = "ListPets")
        val ctx = op.toRequestContext(base, dispatch)
        try {
            assertEquals("ListPets", ctx.operationName)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `toRequestContext leaves the operation name null when the operation declares none`() {
        val dispatch = DispatchContext.default()
        val ctx = TestOp(Method.GET, "/pets").toRequestContext(base, dispatch)
        try {
            assertNull(ctx.operationName)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `toRequest merges a base URL query ahead of the operation query`() {
        val query = QueryParams.builder().add("limit", "20").build()
        val request = TestOp(Method.GET, "/pets", query = query).toRequest("https://api.example.com/v1?sv=X&sig=Y")
        assertEquals("https://api.example.com/v1/pets?sv=X&sig=Y&limit=20", request.url.toExternalForm())
    }

    @Test
    fun `toRequest preserves a base URL query when the operation contributes none`() {
        val request = TestOp(Method.GET, "/pets").toRequest("https://api.example.com?sv=X")
        assertEquals("https://api.example.com/pets?sv=X", request.url.toExternalForm())
    }

    @Test
    fun `toRequest preserves a significant trailing slash when the path is empty`() {
        // An empty path template targets the base resource itself; the base's trailing slash is
        // semantic (some routers distinguish /v1/ from /v1) and must not be silently stripped.
        val request = TestOp(Method.GET, "").toRequest("https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/", request.url.toExternalForm())
    }

    @Test
    fun `toRequest does not double the separator when the base query ends with an ampersand`() {
        val query = QueryParams.builder().add("b", "2").build()
        val request = TestOp(Method.GET, "/pets", query = query).toRequest("https://api.example.com/v1?a=1&")
        assertEquals("https://api.example.com/v1/pets?a=1&b=2", request.url.toExternalForm())
    }

    @Test
    fun `toRequest drops a dangling base query separator when the operation contributes none`() {
        // A trailing '&' on the base query is its own separator, not an empty parameter; with no
        // operation query to join it must not survive as a dangling `?a=1&`.
        val request = TestOp(Method.GET, "/pets").toRequest("https://api.example.com/v1?a=1&")
        assertEquals("https://api.example.com/v1/pets?a=1", request.url.toExternalForm())
    }

    @Test
    fun `toRequest rejects a base URL carrying a fragment`() {
        assertFailsWith<IllegalArgumentException> {
            TestOp(Method.GET, "/pets").toRequest("https://api.example.com/v1#frag")
        }
    }

    @Test
    fun `toRequest throws IllegalArgumentException for a malformed base URL`() {
        assertFailsWith<IllegalArgumentException> {
            TestOp(Method.GET, "/pets").toRequest("api.example.com")
        }
    }
}
