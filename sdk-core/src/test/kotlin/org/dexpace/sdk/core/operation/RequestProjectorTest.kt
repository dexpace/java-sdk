/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.context.CallOptions
import org.dexpace.sdk.core.http.context.CallTimeout
import org.dexpace.sdk.core.http.context.ContextStore
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.context.ResponseValidation
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.BufferedSink
import java.net.URL
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private class ProjectorStubBody : RequestBody() {
    override fun mediaType(): MediaType? = null

    override fun writeTo(sink: BufferedSink): Unit = throw UnsupportedOperationException("not written in tests")
}

class RequestProjectorTest {
    private val ownedKeys: MutableList<String> = mutableListOf()

    @AfterTest
    fun evict() {
        for (key in ownedKeys) ContextStore.remove(key)
    }

    private val base = URL("https://api.example.test/v1/")

    @Test
    fun `substitutes a path placeholder`() {
        val params =
            object : OperationParams {
                override fun pathParams(): List<PathParam> = listOf(PathParam("id", "42"))
            }
        val request = RequestProjector.project(base, Method.GET, "users/{id}", params)
        assertEquals("https://api.example.test/v1/users/42", request.url.toExternalForm())
        assertEquals(Method.GET, request.method)
    }

    @Test
    fun `percent-encodes a path value including slashes`() {
        val params =
            object : OperationParams {
                override fun pathParams(): List<PathParam> = listOf(PathParam("name", "a/b c"))
            }
        val request = RequestProjector.project(base, Method.GET, "items/{name}", params)
        assertEquals("https://api.example.test/v1/items/a%2Fb%20c", request.url.toExternalForm())
    }

    @Test
    fun `appends an ordered query string with repetition`() {
        val params =
            object : OperationParams {
                override fun queryParams(): List<QueryParam> =
                    listOf(
                        QueryParam("tag", "a"),
                        QueryParam("tag", "b"),
                        QueryParam("limit", "10"),
                    )
            }
        val request = RequestProjector.project(base, Method.GET, "search", params)
        assertEquals("https://api.example.test/v1/search?tag=a&tag=b&limit=10", request.url.toExternalForm())
    }

    @Test
    fun `encodes query name and value and renders a valueless param`() {
        val params =
            object : OperationParams {
                override fun queryParams(): List<QueryParam> =
                    listOf(
                        QueryParam("q", "hello world&x"),
                        QueryParam("flag", null),
                    )
            }
        val request = RequestProjector.project(base, Method.GET, "search", params)
        assertEquals("https://api.example.test/v1/search?q=hello%20world%26x&flag", request.url.toExternalForm())
    }

    @Test
    fun `applies headers and body`() {
        val body = ProjectorStubBody()
        val headers = Headers.Builder().add("X-Trace", "abc").build()
        val params =
            object : OperationParams {
                override fun headers(): Headers = headers

                override fun body(): RequestBody = body
            }
        val request = RequestProjector.project(base, Method.POST, "users", params)
        assertEquals("abc", request.headers.get("X-Trace"))
        assertSame(body, request.body)
        assertEquals(Method.POST, request.method)
    }

    @Test
    fun `missing path param fails`() {
        assertFailsWith<IllegalArgumentException> {
            RequestProjector.project(base, Method.GET, "users/{id}", OperationParams.NONE)
        }
    }

    @Test
    fun `extra path param without a placeholder fails`() {
        val params =
            object : OperationParams {
                override fun pathParams(): List<PathParam> = listOf(PathParam("missing", "x"))
            }
        assertFailsWith<IllegalArgumentException> {
            RequestProjector.project(base, Method.GET, "users", params)
        }
    }

    @Test
    fun `projectInto promotes a dispatch context and carries its call options forward`() {
        val options =
            CallOptions.builder()
                .timeout(CallTimeout.ofCall(Duration.ofSeconds(5)))
                .responseValidation(ResponseValidation.DISABLED)
                .build()
        val dispatch =
            DispatchContext(
                instrumentationContext = DispatchContext.default().instrumentationContext,
                callOptions = options,
            )
        ownedKeys.add(dispatch.callKey)

        val params =
            object : OperationParams {
                override fun pathParams(): List<PathParam> = listOf(PathParam("id", "7"))
            }
        val requestCtx = RequestProjector.projectInto(dispatch, base, Method.GET, "users/{id}", params)

        assertEquals("https://api.example.test/v1/users/7", requestCtx.request.url.toExternalForm())
        assertSame(options, requestCtx.callOptions)
        assertEquals(dispatch.callKey, requestCtx.callKey)
        assertSame(requestCtx, ContextStore.get(requestCtx.callKey))
    }
}
