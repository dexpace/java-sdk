package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import java.net.MalformedURLException
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class RequestTest {

    @Test
    fun `newBuilder round trip preserves all fields`() {
        val original = Request.builder()
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
        val original = Request.builder()
            .url("https://api.example.test/path")
            .method(Method.GET)
            .addHeader("X-A", "1")
            .build()

        val mutated = original.newBuilder()
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
        val source = Request.builder()
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
        val req = Request.builder()
            .url("https://example.test")
            .method(Method.GET)
            .addHeader("X-Multi", listOf("a", "b", "c"))
            .build()
        assertEquals(listOf("a", "b", "c"), req.headers.values("X-Multi"))
    }

    @Test
    fun `addHeader with list adds to existing values`() {
        val req = Request.builder()
            .url("https://example.test")
            .method(Method.GET)
            .addHeader("X-Multi", "first")
            .addHeader("X-Multi", listOf("b", "c"))
            .build()
        assertEquals(listOf("first", "b", "c"), req.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader with list replaces previous values`() {
        val req = Request.builder()
            .url("https://example.test")
            .method(Method.GET)
            .addHeader("X-Multi", "old")
            .setHeader("X-Multi", listOf("a", "b"))
            .build()
        assertEquals(listOf("a", "b"), req.headers.values("X-Multi"))
    }

    @Test
    fun `setHeader single replaces previous values`() {
        val req = Request.builder()
            .url("https://example.test")
            .method(Method.GET)
            .addHeader("X-A", "old")
            .setHeader("X-A", "new")
            .build()
        assertEquals(listOf("new"), req.headers.values("X-A"))
    }

    @Test
    fun `removeHeader by name drops all values`() {
        val req = Request.builder()
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
        val req = Request.builder()
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
        val req = Request.builder()
            .url("https://example.test")
            .method(Method.POST)
            .body(payload)
            .build()
        assertEquals(payload, req.body)
    }

    @Test
    fun `headers replaces the entire header set`() {
        val headers = Headers.builder().add("X-A", "1").add("X-B", "2").build()
        val req = Request.builder()
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
        val ex = assertFailsWith<IllegalStateException> {
            Request.builder().url("https://example.test").build()
        }
        assertEquals("Method is required.", ex.message)
    }

    @Test
    fun `build throws when url is missing`() {
        val ex = assertFailsWith<IllegalStateException> {
            Request.builder().method(Method.GET).build()
        }
        assertEquals("URL is required.", ex.message)
    }
}
