package org.dexpace.sdk.core.http.request

import kotlin.test.Test
import kotlin.test.assertEquals

class MethodTest {
    @Test
    fun `all methods carry the canonical uppercase token`() {
        assertEquals("GET", Method.GET.method)
        assertEquals("POST", Method.POST.method)
        assertEquals("PUT", Method.PUT.method)
        assertEquals("DELETE", Method.DELETE.method)
        assertEquals("PATCH", Method.PATCH.method)
        assertEquals("HEAD", Method.HEAD.method)
        assertEquals("OPTIONS", Method.OPTIONS.method)
        assertEquals("TRACE", Method.TRACE.method)
        assertEquals("CONNECT", Method.CONNECT.method)
    }

    @Test
    fun `toString returns the wire token for every method`() {
        for (m in Method.entries) {
            assertEquals(m.method, m.toString(), "toString mismatch for $m")
        }
    }

    @Test
    fun `name returns the Kotlin enum name`() {
        assertEquals("GET", Method.GET.name)
        assertEquals("POST", Method.POST.name)
        assertEquals("CONNECT", Method.CONNECT.name)
    }

    @Test
    fun `valueOf round trips through name`() {
        for (m in Method.entries) {
            assertEquals(m, Method.valueOf(m.name))
        }
    }

    @Test
    fun `entries enumerates exactly nine methods`() {
        assertEquals(9, Method.entries.size)
    }
}
