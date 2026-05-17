package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.IdentityHashMap
import java.util.stream.Collectors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenPaginationTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/list")
            .method(Method.GET)
            .build()

    /**
     * Parses `items=<csv>\ntoken=<value-or-empty>` once; returns (items, nextToken).
     */
    private fun parsePayload(resp: Response): Pair<List<String>, String?> {
        val body = resp.body!!.source().use { it.readUtf8() }
        val itemsLine = body.lineSequence().firstOrNull { it.startsWith("items=") } ?: "items="
        val tokenLine = body.lineSequence().firstOrNull { it.startsWith("token=") } ?: "token="
        val itemsRaw = itemsLine.removePrefix("items=")
        val tokenRaw = tokenLine.removePrefix("token=")
        val items = if (itemsRaw.isEmpty()) emptyList() else itemsRaw.split(",")
        val token: String? = tokenRaw.ifEmpty { null }
        return Pair(items, token)
    }

    private fun cachedExtractors(): Pair<(Response) -> List<String>, (Response) -> String?> {
        val cache: MutableMap<Response, Pair<List<String>, String?>> = IdentityHashMap()
        val items: (Response) -> List<String> = { r ->
            cache.getOrPut(r) { parsePayload(r) }.first
        }
        val token: (Response) -> String? = { r ->
            cache.getOrPut(r) { parsePayload(r) }.second
        }
        return Pair(items, token)
    }

    @Test
    fun `token pagination walks pages and stops on empty token`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/list") { req ->
            textResponse(req, "items=alpha,beta\ntoken=t1")
        }
        client.on("https://api.example.com/list?page_token=t1") { req ->
            textResponse(req, "items=gamma\ntoken=t2")
        }
        client.on("https://api.example.com/list?page_token=t2") { req ->
            textResponse(req, "items=delta,epsilon\ntoken=")
        }

        val (items, token) = cachedExtractors()
        val strategy = TokenPaginationStrategy(items, token)
        val paginator = Paginator(client, initialRequest(), strategy)
        val collected = paginator.iterateAll().toList()
        assertEquals(listOf("alpha", "beta", "gamma", "delta", "epsilon"), collected)
        assertEquals(3, client.callCount)
    }

    @Test
    fun `custom token query param name works`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/list") { req ->
            textResponse(req, "items=x\ntoken=ab")
        }
        client.on("https://api.example.com/list?nextToken=ab") { req ->
            textResponse(req, "items=y\ntoken=")
        }

        val (items, token) = cachedExtractors()
        val strategy =
            TokenPaginationStrategy<String>(items, token, tokenQueryParam = "nextToken")
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("x", "y"), paginator.iterateAll().toList())
        assertEquals(2, client.callCount)
    }

    @Test
    fun `streamAll yields the same items as iterateAll`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/list") { req ->
            textResponse(req, "items=1,2\ntoken=t1")
        }
        client.on("https://api.example.com/list?page_token=t1") { req ->
            textResponse(req, "items=3,4\ntoken=")
        }

        val (items, token) = cachedExtractors()
        val strategy = TokenPaginationStrategy(items, token)
        val paginator = Paginator(client, initialRequest(), strategy)
        val streamed: List<String> = paginator.streamAll().collect(Collectors.toList())
        assertEquals(listOf("1", "2", "3", "4"), streamed)
    }

    @Test
    fun `token with special characters is URL encoded in next request`() {
        // Tokens may contain `=` `+` `/` characters (base64) — the rebuilder must URL-encode
        // them so the server sees the original token unmangled.
        val rawToken = "a+b/c="
        val encoded = "a%2Bb%2Fc%3D"
        val client = StubHttpClient()
        client.on("https://api.example.com/list") { req ->
            textResponse(req, "items=one\ntoken=$rawToken")
        }
        client.on("https://api.example.com/list?page_token=$encoded") { req ->
            textResponse(req, "items=two\ntoken=")
        }

        val (items, token) = cachedExtractors()
        val strategy = TokenPaginationStrategy(items, token)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("one", "two"), paginator.iterateAll().toList())
        assertEquals(
            listOf(
                "https://api.example.com/list",
                "https://api.example.com/list?page_token=$encoded",
            ),
            client.receivedUrls,
        )
    }
}
