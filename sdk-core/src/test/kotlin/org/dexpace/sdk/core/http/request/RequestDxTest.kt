/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequestDxTest {
    // -------------------------------------------------------------------------
    // T5 — typed HttpHeaderName overloads on RequestBuilder
    // -------------------------------------------------------------------------

    @Test
    fun `addHeader typed HttpHeaderName and String adds header`() {
        val req =
            Request.builder()
                .url("https://x.example")
                .addHeader(HttpHeaderName.ACCEPT, "application/json")
                .build()
        assertEquals("application/json", req.headers.get(HttpHeaderName.ACCEPT))
    }

    @Test
    fun `addHeader typed HttpHeaderName and List adds all values`() {
        val req =
            Request.builder()
                .url("https://x.example")
                .addHeader(HttpHeaderName.ACCEPT, listOf("application/json", "text/plain"))
                .build()
        assertEquals(listOf("application/json", "text/plain"), req.headers.values(HttpHeaderName.ACCEPT))
    }

    @Test
    fun `setHeader typed HttpHeaderName and String replaces existing`() {
        val req =
            Request.builder()
                .url("https://x.example")
                .addHeader(HttpHeaderName.ACCEPT, "text/plain")
                .setHeader(HttpHeaderName.ACCEPT, "application/json")
                .build()
        assertEquals(listOf("application/json"), req.headers.values(HttpHeaderName.ACCEPT))
    }

    @Test
    fun `setHeader typed HttpHeaderName and List replaces existing`() {
        val req =
            Request.builder()
                .url("https://x.example")
                .addHeader(HttpHeaderName.ACCEPT, "text/plain")
                .setHeader(HttpHeaderName.ACCEPT, listOf("application/json", "application/xml"))
                .build()
        assertEquals(listOf("application/json", "application/xml"), req.headers.values(HttpHeaderName.ACCEPT))
    }

    @Test
    fun `addHeader typed HttpHeaderName and MediaType sets header as string`() {
        val req =
            Request.builder()
                .url("https://x.example")
                .addHeader(HttpHeaderName.ACCEPT, MediaType.APPLICATION_JSON)
                .build()
        assertEquals("application/json", req.headers.get(HttpHeaderName.ACCEPT))
    }

    // -------------------------------------------------------------------------
    // T6 — url(String) throws IllegalArgumentException; url(URI) overload
    // -------------------------------------------------------------------------

    @Test
    fun `url String throws IllegalArgumentException on bad url`() {
        assertFailsWith<IllegalArgumentException> {
            Request.builder().url("::bad")
        }
    }

    @Test
    fun `url String throws IllegalArgumentException not checked exception`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                Request.builder().url("not-a-valid-url")
            }
        assertNotNull(ex.message)
    }

    @Test
    fun `url URI builds a request whose url matches`() {
        val uri = URI.create("https://x.example/y")
        val req = Request.builder().url(uri).build()
        assertEquals(uri.toURL().toExternalForm(), req.url.toExternalForm())
    }

    @Test
    fun `url URI with path and query builds correctly`() {
        val uri = URI.create("https://api.example.com/v1/items?q=hello")
        val req = Request.builder().url(uri).build()
        assertEquals("https://api.example.com/v1/items?q=hello", req.url.toExternalForm())
    }

    // -------------------------------------------------------------------------
    // T7 — default GET, verb methods, companion factories
    // -------------------------------------------------------------------------

    @Test
    fun `build without explicit method defaults to GET`() {
        val req = Request.builder().url("https://example.test").build()
        assertEquals(Method.GET, req.method)
    }

    @Test
    fun `verb get() sets method to GET and clears body`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .get()
                .build()
        assertEquals(Method.GET, req.method)
        assertNull(req.body)
    }

    @Test
    fun `verb post() sets method to POST and body`() {
        val body = RequestBody.create("payload", null)
        val req =
            Request.builder()
                .url("https://example.test")
                .post(body)
                .build()
        assertEquals(Method.POST, req.method)
        assertEquals(body, req.body)
    }

    @Test
    fun `verb put() sets method to PUT and body`() {
        val body = RequestBody.create("payload", null)
        val req =
            Request.builder()
                .url("https://example.test")
                .put(body)
                .build()
        assertEquals(Method.PUT, req.method)
        assertEquals(body, req.body)
    }

    @Test
    fun `verb delete() sets method to DELETE`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .delete()
                .build()
        assertEquals(Method.DELETE, req.method)
    }

    @Test
    fun `verb delete() clears a previously-set body`() {
        val body = RequestBody.create("payload", null)
        val req =
            Request.builder()
                .url("https://example.test")
                .post(body)
                .delete()
                .build()
        assertEquals(Method.DELETE, req.method)
        assertNull(req.body)
    }

    @Test
    fun `verb head() sets method to HEAD and clears body`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .head()
                .build()
        assertEquals(Method.HEAD, req.method)
        assertNull(req.body)
    }

    @Test
    fun `verb patch() sets method to PATCH and body`() {
        val body = RequestBody.create("payload", null)
        val req =
            Request.builder()
                .url("https://example.test")
                .patch(body)
                .build()
        assertEquals(Method.PATCH, req.method)
        assertEquals(body, req.body)
    }

    @Test
    fun `Request get companion factory builds a GET request`() {
        val req = Request.get("https://example.test")
        assertEquals(Method.GET, req.method)
        assertNull(req.body)
    }

    @Test
    fun `Request post companion factory builds a POST request with body`() {
        val body = RequestBody.create("x", null)
        val req = Request.post("https://example.test", body)
        assertEquals(Method.POST, req.method)
        assertEquals(body, req.body)
    }
}
