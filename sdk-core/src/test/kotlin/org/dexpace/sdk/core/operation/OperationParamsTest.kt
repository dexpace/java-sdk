/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.BufferedSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A bodyless probe body that needs no installed IO provider — never written in these tests. */
private class ParamsStubBody : RequestBody() {
    override fun mediaType(): MediaType? = null

    override fun writeTo(sink: BufferedSink): Unit = throw UnsupportedOperationException("not written in tests")
}

class OperationParamsTest {
    @Test
    fun `NONE projects nothing`() {
        val none = OperationParams.NONE
        assertTrue(none.pathParams().isEmpty())
        assertTrue(none.queryParams().isEmpty())
        assertEquals(OperationParams.EMPTY_HEADERS, none.headers())
        assertNull(none.body())
    }

    @Test
    fun `default methods only require overriding the contributed parts`() {
        val queryOnly =
            object : OperationParams {
                override fun queryParams(): List<QueryParam> = listOf(QueryParam("limit", "10"))
            }
        assertEquals(listOf(QueryParam("limit", "10")), queryOnly.queryParams())
        // Untouched projections keep their empty defaults.
        assertTrue(queryOnly.pathParams().isEmpty())
        assertNull(queryOnly.body())
        assertEquals(OperationParams.EMPTY_HEADERS, queryOnly.headers())
    }

    @Test
    fun `a fully-populated projection exposes every part`() {
        val body = ParamsStubBody()
        val headers = Headers.Builder().add("X-Trace", "abc").build()
        val params =
            object : OperationParams {
                override fun pathParams(): List<PathParam> = listOf(PathParam("id", "42"))

                override fun queryParams(): List<QueryParam> = listOf(QueryParam("expand", "all"))

                override fun headers(): Headers = headers

                override fun body(): RequestBody = body
            }

        assertEquals(listOf(PathParam("id", "42")), params.pathParams())
        assertEquals(listOf(QueryParam("expand", "all")), params.queryParams())
        assertSame(headers, params.headers())
        assertSame(body, params.body())
    }
}
