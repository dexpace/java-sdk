package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LazinessTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) emptyList() else body.split(",")
    }

    @Test
    fun `constructing a paginator triggers zero HTTP calls`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "a,b")
        }
        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        // No iteration started yet — no HTTP call should have happened.
        assertEquals(0, client.callCount)

        // Even calling iterateAll() (without iterating) is lazy — the iterable is a thunk.
        val iterable = paginator.iterateAll()
        assertEquals(0, client.callCount)

        // Pulling the first iterator + first hasNext() triggers the first fetch only.
        val iter = iterable.iterator()
        assertEquals(0, client.callCount)
        iter.hasNext()
        assertEquals(1, client.callCount)
    }

    @Test
    fun `each consumed page triggers exactly one fetch`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "a,b")
        }
        client.on("https://api.example.com/items?page=2") { req ->
            textResponse(req, "c,d")
        }
        client.on("https://api.example.com/items?page=3") { req ->
            textResponse(req, "")
        }
        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        val iter = paginator.iterateAll().iterator()

        // Consume page 1: 1 fetch.
        assertTrue(iter.hasNext())
        assertEquals(1, client.callCount)
        assertEquals("a", iter.next())
        assertEquals(1, client.callCount)
        assertEquals("b", iter.next())
        assertEquals(1, client.callCount)

        // Cross page boundary → exactly one more fetch.
        assertTrue(iter.hasNext())
        assertEquals(2, client.callCount)
        assertEquals("c", iter.next())
        assertEquals(2, client.callCount)
        assertEquals("d", iter.next())

        // Final boundary triggers the empty-page fetch.
        assertTrue(!iter.hasNext()) // Should now know it's done (page 3 was empty).
        assertEquals(3, client.callCount)
        // Idempotent — subsequent hasNext() calls do not re-fetch.
        assertTrue(!iter.hasNext())
        assertEquals(3, client.callCount)
    }

    @Test
    fun `independent iterators each drive their own fetch sequence`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "a")
        }
        client.on("https://api.example.com/items?page=2") { req ->
            textResponse(req, "")
        }
        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)

        val first = paginator.iterateAll().toList()
        val second = paginator.iterateAll().toList()
        assertEquals(first, second)
        // Two iterations × 2 fetches each = 4 total.
        assertEquals(4, client.callCount)
    }

    @Test
    fun `taking just one item only fetches the first page`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(req, "first,second,third")
        }
        // Page 2 is enqueued but should never be fetched if the caller stops at item 1.
        client.on("https://api.example.com/items?page=2") { req ->
            textResponse(req, "should-not-be-fetched")
        }
        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)

        val one = paginator.iterateAll().iterator().next()
        assertEquals("first", one)
        assertEquals(1, client.callCount)
    }
}
