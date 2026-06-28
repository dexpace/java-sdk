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
}
