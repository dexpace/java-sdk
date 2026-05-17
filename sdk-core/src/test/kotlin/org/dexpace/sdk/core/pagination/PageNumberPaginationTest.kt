package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PageNumberPaginationTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/things")
            .method(Method.GET)
            .build()

    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) {
            emptyList()
        } else {
            body.split(",")
        }
    }

    @Test
    fun `page-number pagination walks pages 1 2 3 then stops on empty page 4`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/things") { req ->
            textResponse(req, "a,b,c")
        }
        client.on("https://api.example.com/things?page=2") { req ->
            textResponse(req, "d,e,f")
        }
        client.on("https://api.example.com/things?page=3") { req ->
            textResponse(req, "g,h,i")
        }
        client.on("https://api.example.com/things?page=4") { req ->
            textResponse(req, "")
        }

        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)

        val collected: List<String> = paginator.iterateAll().toList()

        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), collected)
        assertEquals(4, client.callCount)
        assertEquals(
            listOf(
                "https://api.example.com/things",
                "https://api.example.com/things?page=2",
                "https://api.example.com/things?page=3",
                "https://api.example.com/things?page=4",
            ),
            client.receivedUrls,
        )
    }

    @Test
    fun `custom pageParam and startPage are honored`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/things") { req ->
            textResponse(req, "x")
        }
        // startPage=0 means first request has no `pageNumber` param; parser reads "absent" as 0,
        // so next request is `pageNumber=1`. (Server uses 0-indexed pages.)
        client.on("https://api.example.com/things?pageNumber=1") { req ->
            textResponse(req, "y")
        }
        client.on("https://api.example.com/things?pageNumber=2") { req ->
            textResponse(req, "")
        }

        val strategy =
            PageNumberPaginationStrategy<String>(
                itemsExtractor,
                pageParam = "pageNumber",
                startPage = 0,
            )
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("x", "y"), paginator.iterateAll().toList())
        assertEquals(3, client.callCount)
    }

    @Test
    fun `existing page query param on initial request is respected as the starting point`() {
        val initial =
            Request.builder()
                .url("https://api.example.com/things?page=5")
                .method(Method.GET)
                .build()
        val client = StubHttpClient()
        client.on("https://api.example.com/things?page=5") { req ->
            textResponse(req, "p,q,r")
        }
        client.on("https://api.example.com/things?page=6") { req ->
            textResponse(req, "")
        }

        val strategy = PageNumberPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initial, strategy)
        assertEquals(listOf("p", "q", "r"), paginator.iterateAll().toList())
        assertEquals(2, client.callCount)
    }
}
