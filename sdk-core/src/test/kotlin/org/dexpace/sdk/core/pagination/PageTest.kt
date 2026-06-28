package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun request(): Request = Request.builder().method(Method.GET).url(URL("https://api.example.com/items")).build()

    @Test
    fun `explicit constructor exposes fields and defaults links to null`() {
        val page = Page(items = listOf("a", "b"), statusCode = 200, headers = Headers.builder().build(), request = request())
        assertEquals(listOf("a", "b"), page.items)
        assertEquals(200, page.statusCode)
        assertNull(page.nextLink)
        assertNull(page.continuationToken)
    }

    @Test
    fun `from snapshots status, headers and request and does not close the response`() {
        val closeCount = AtomicInteger(0)
        val body = object : ResponseBody() {
            override fun mediaType() = null
            override fun contentLength() = -1L
            override fun source() = throw UnsupportedOperationException("not needed")
            override fun close() { closeCount.incrementAndGet() }
        }
        val response = Response.builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(204))
            .headers(Headers.builder().add("X-Total", "9").build())
            .body(body)
            .build()

        val page = Page.from(response, items = listOf(1, 2, 3), nextLink = "https://api.example.com/items?page=2")

        assertEquals(204, page.statusCode)
        assertEquals(listOf("9"), page.headers.values("X-Total"))
        assertEquals("https://api.example.com/items", page.request?.url.toString())
        assertEquals("https://api.example.com/items?page=2", page.nextLink)
        assertEquals(0, closeCount.get()) // from() must NOT close the response
    }
}
