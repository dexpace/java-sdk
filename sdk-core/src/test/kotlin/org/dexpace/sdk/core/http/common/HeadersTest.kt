package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadersTest {

    @Test
    fun `string-set values are visible via typed get`() {
        val headers = Headers.builder()
            .set("authorization", "Bearer abc")
            .build()

        // The String API stores with case-insensitive normalisation; the typed API must
        // see the same value because both use the same lower-cased internal key.
        assertEquals("Bearer abc", headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `typed-set values are visible via string get`() {
        val headers = Headers.builder()
            .set(HttpHeaderName.CONTENT_TYPE, "application/json")
            .build()

        assertEquals("application/json", headers.get("Content-Type"))
        assertEquals("application/json", headers.get("content-type"))
    }

    @Test
    fun `add accumulates multiple values for the same typed name`() {
        val headers = Headers.builder()
            .set(HttpHeaderName.SET_COOKIE, "a=1")
            .add(HttpHeaderName.SET_COOKIE, "b=2")
            .build()

        val values = headers.values(HttpHeaderName.SET_COOKIE)
        assertEquals(listOf("a=1", "b=2"), values)
        // First-value lookup picks the earliest entry.
        assertEquals("a=1", headers.get(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `set with null value removes the header (string overload)`() {
        val headers = Headers.builder()
            .set("X-Trace-Id", "trace-1")
            .set("X-Trace-Id", null)
            .build()

        assertFalse(headers.contains("X-Trace-Id"))
        assertNull(headers.get("X-Trace-Id"))
    }

    @Test
    fun `set with null value removes the header (typed overload)`() {
        val headers = Headers.builder()
            .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
            .set(HttpHeaderName.AUTHORIZATION, null)
            .build()

        assertFalse(headers.contains(HttpHeaderName.AUTHORIZATION))
        assertNull(headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `set replaces previously accumulated values`() {
        val headers = Headers.builder()
            .add(HttpHeaderName.SET_COOKIE, "a=1")
            .add(HttpHeaderName.SET_COOKIE, "b=2")
            .set(HttpHeaderName.SET_COOKIE, "c=3")
            .build()

        assertEquals(listOf("c=3"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `addAll merges values from another Headers instance`() {
        val a = Headers.builder()
            .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
            .add(HttpHeaderName.SET_COOKIE, "a=1")
            .build()
        val b = Headers.builder()
            .add(HttpHeaderName.SET_COOKIE, "b=2")
            .set(HttpHeaderName.CONTENT_TYPE, "application/json")
            .build()

        val merged = a.newBuilder().addAll(b).build()

        assertEquals("Bearer abc", merged.get(HttpHeaderName.AUTHORIZATION))
        assertEquals("application/json", merged.get(HttpHeaderName.CONTENT_TYPE))
        // Multi-value names accumulate across the merge — neither side is dropped.
        assertEquals(listOf("a=1", "b=2"), merged.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `contains works for both string and typed names`() {
        val headers = Headers.builder()
            .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
            .build()

        assertTrue(headers.contains("Authorization"))
        assertTrue(headers.contains("authorization"))
        assertTrue(headers.contains(HttpHeaderName.AUTHORIZATION))
        assertFalse(headers.contains("X-Missing"))
        assertFalse(headers.contains(HttpHeaderName.X_REQUEST_ID))
    }

    @Test
    fun `remove with typed name clears all values for that header`() {
        val headers = Headers.builder()
            .add(HttpHeaderName.SET_COOKIE, "a=1")
            .add(HttpHeaderName.SET_COOKIE, "b=2")
            .remove(HttpHeaderName.SET_COOKIE)
            .build()

        assertFalse(headers.contains(HttpHeaderName.SET_COOKIE))
        assertTrue(headers.values(HttpHeaderName.SET_COOKIE).isEmpty())
    }

    @Test
    fun `add with list overload appends all values`() {
        val headers = Headers.builder()
            .add(HttpHeaderName.SET_COOKIE, listOf("a=1", "b=2"))
            .add(HttpHeaderName.SET_COOKIE, "c=3")
            .build()

        assertEquals(listOf("a=1", "b=2", "c=3"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `set with list overload replaces values`() {
        val headers = Headers.builder()
            .add(HttpHeaderName.SET_COOKIE, "old=1")
            .set(HttpHeaderName.SET_COOKIE, listOf("new=1", "new=2"))
            .build()

        assertEquals(listOf("new=1", "new=2"), headers.values(HttpHeaderName.SET_COOKIE))
    }
}
