/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import java.net.MalformedURLException
import java.net.URL
import java.net.URLStreamHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTest {
    @Test
    fun `newBuilder round trip preserves all fields`() {
        val original =
            Request.builder()
                .url("https://api.example.test/path")
                .method(Method.POST)
                .addHeader("X-A", "1")
                .build()

        val copy = original.newBuilder().build()

        assertEquals(original.method, copy.method)
        assertEquals(original.url, copy.url)
        assertEquals(original.headers, copy.headers)
        assertEquals(original.body, copy.body)
        // newBuilder must produce a *new* Request — but data class equality should hold
        assertEquals(original, copy)
    }

    @Test
    fun `newBuilder allows mutation without disturbing source`() {
        val original =
            Request.builder()
                .url("https://api.example.test/path")
                .method(Method.GET)
                .addHeader("X-A", "1")
                .build()

        val mutated =
            original.newBuilder()
                .method(Method.PUT)
                .addHeader("X-B", "2")
                .build()

        assertEquals(Method.GET, original.method)
        assertEquals(Method.PUT, mutated.method)
        assertEquals("1", original.headers.get("X-A"))
        assertNull(original.headers.get("X-B"))
        assertEquals("2", mutated.headers.get("X-B"))
        assertNotSame(original, mutated)
    }

    @Test
    fun `builder static factory returns fresh empty builder`() {
        val b1 = Request.builder()
        val b2 = Request.builder()
        assertNotSame(b1, b2)
    }

    @Test
    fun `RequestBuilder copy constructor copies all fields`() {
        val source =
            Request.builder()
                .url("https://api.example.test/path")
                .method(Method.PATCH)
                .addHeader("X-A", "1")
                .body(RequestBody.create("body", null))
                .build()

        val rebuilt = Request.RequestBuilder(source).build()

        assertEquals(source.method, rebuilt.method)
        assertEquals(source.url, rebuilt.url)
        assertEquals(source.headers, rebuilt.headers)
        assertEquals(source.body, rebuilt.body)
    }

    @Test
    fun `addHeader with list accumulates all values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-Multi", listOf("a", "b", "c"))
                .build()
        assertEquals(listOf("a", "b", "c"), req.headers.values("X-Multi"))
    }

    @Test
    fun `addHeader with list adds to existing values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-Multi", "first")
                .addHeader("X-Multi", listOf("b", "c"))
                .build()
        assertEquals(listOf("first", "b", "c"), req.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader with list replaces previous values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-Multi", "old")
                .setHeader("X-Multi", listOf("a", "b"))
                .build()
        assertEquals(listOf("a", "b"), req.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader single replaces previous values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-A", "old")
                .setHeader("X-A", "new")
                .build()
        assertEquals(listOf("new"), req.headers.values("X-A"))
    }

    @Test
    fun `removeHeader by name drops all values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-A", "v1")
                .addHeader("X-A", "v2")
                .removeHeader("X-A")
                .build()
        assertEquals(emptyList(), req.headers.values("X-A"))
    }

    @Test
    fun `removeHeader by HttpHeaderName drops all values`() {
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("Authorization", "Bearer t")
                .removeHeader(HttpHeaderName.AUTHORIZATION)
                .build()
        assertEquals(emptyList(), req.headers.values("Authorization"))
    }

    @Test
    fun `body sets the request body`() {
        val payload = RequestBody.create("hello", null)
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.POST)
                .body(payload)
                .build()
        assertEquals(payload, req.body)
    }

    @Test
    fun `headers replaces the entire header set`() {
        val headers = Headers.builder().add("X-A", "1").add("X-B", "2").build()
        val req =
            Request.builder()
                .url("https://example.test")
                .method(Method.GET)
                .addHeader("X-Existing", "ignored")
                .headers(headers)
                .build()
        assertEquals(listOf("1"), req.headers.values("X-A"))
        assertEquals(listOf("2"), req.headers.values("X-B"))
        assertEquals(emptyList(), req.headers.values("X-Existing"))
    }

    @Test
    fun `url URL setter accepts a URL object`() {
        val u = URL("https://example.test/path")
        val req = Request.builder().method(Method.GET).url(u).build()
        assertEquals(u, req.url)
    }

    @Test
    fun `url String setter throws MalformedURLException on bad input`() {
        assertFailsWith<MalformedURLException> {
            Request.builder().url("not-a-valid-url")
        }
    }

    @Test
    fun `build throws when method is missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                Request.builder().url("https://example.test").build()
            }
        assertEquals("method is required", ex.message)
    }

    @Test
    fun `build throws when url is missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                Request.builder().method(Method.GET).build()
            }
        assertEquals("url is required", ex.message)
    }

    // ---------------------------------------------------------------------
    // Body / method compatibility — a body on a body-forbidden method is
    // rejected at build time so transports never have to disagree on it.
    // ---------------------------------------------------------------------

    @Test
    fun `build rejects a body on a body-forbidden method`() {
        for (method in listOf(Method.GET, Method.HEAD, Method.TRACE)) {
            val ex =
                assertFailsWith<IllegalArgumentException>("expected rejection for $method") {
                    Request.builder()
                        .url("https://example.test")
                        .method(method)
                        .body(RequestBody.create("x", null))
                        .build()
                }
            assertTrue(
                ex.message!!.contains("$method must not carry a request body"),
                "unexpected message for $method: ${ex.message}",
            )
        }
    }

    @Test
    fun `build allows a body on a body-permitting method`() {
        for (method in listOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE, Method.OPTIONS)) {
            val payload = RequestBody.create("x", null)
            val req =
                Request.builder()
                    .url("https://example.test")
                    .method(method)
                    .body(payload)
                    .build()
            assertEquals(payload, req.body, "body should be retained for $method")
        }
    }

    @Test
    fun `build allows a body-less body-forbidden method`() {
        for (method in listOf(Method.GET, Method.HEAD, Method.TRACE)) {
            val req =
                Request.builder()
                    .url("https://example.test")
                    .method(method)
                    .build()
            assertNull(req.body, "body should stay null for $method")
        }
    }

    @Test
    fun `newBuilder switching to a body-forbidden method while a body is set is rejected`() {
        val post =
            Request.builder()
                .url("https://example.test")
                .method(Method.POST)
                .body(RequestBody.create("x", null))
                .build()

        assertFailsWith<IllegalArgumentException> {
            post.newBuilder().method(Method.GET).build()
        }
    }

    // ---------------------------------------------------------------------
    // Equality — compares url by external form, never resolving DNS.
    // ---------------------------------------------------------------------

    @Test
    fun `requests with different hosts are not equal`() {
        val a =
            Request.builder()
                .url("https://host-a.example/path")
                .method(Method.GET)
                .build()
        val b =
            Request.builder()
                .url("https://host-b.example/path")
                .method(Method.GET)
                .build()

        assertNotEquals(a, b)
    }

    @Test
    fun `equal requests stay equal with consistent hashCode`() {
        val a =
            Request.builder()
                .url("https://api.example.test/path")
                .method(Method.POST)
                .addHeader("X-A", "1")
                .build()
        val b =
            Request.builder()
                .url("https://api.example.test/path")
                .method(Method.POST)
                .addHeader("X-A", "1")
                .build()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // Stable across repeated invocations.
        assertEquals(a.hashCode(), a.hashCode())
    }

    @Test
    fun `requests differing only by url path are not equal`() {
        val a =
            Request.builder()
                .url("https://api.example.test/one")
                .method(Method.GET)
                .build()
        val b =
            Request.builder()
                .url("https://api.example.test/two")
                .method(Method.GET)
                .build()

        assertNotEquals(a, b)
    }

    @Test
    fun `equals and hashCode perform no network IO`() {
        // A URLStreamHandler whose equals/hashCode/getHostAddress throw if the JDK URL machinery
        // ever tries to resolve the host. java.net.URL.equals/hashCode delegate to the handler and
        // would trip these; Request must compare via toExternalForm() and never touch them.
        val exploding =
            object : URLStreamHandler() {
                override fun openConnection(u: URL) =
                    throw AssertionError("openConnection must not be called during equality")

                override fun equals(
                    u1: URL?,
                    u2: URL?,
                ): Boolean = throw AssertionError("URLStreamHandler.equals (DNS) must not be called")

                override fun hashCode(u: URL): Int =
                    throw AssertionError("URLStreamHandler.hashCode (DNS) must not be called")
            }

        val urlA = URL("https", "host-a.example", -1, "/path", exploding)
        val urlB = URL("https", "host-a.example", -1, "/path", exploding)

        val a = Request.builder().url(urlA).method(Method.GET).build()
        val b = Request.builder().url(urlB).method(Method.GET).build()

        // These calls would throw AssertionError if Request delegated to URL.equals/hashCode.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a == b)
    }
}
