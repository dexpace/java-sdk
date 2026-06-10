/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkHeaderPaginationTest {
    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/repo/issues")
            .method(Method.GET)
            .build()

    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) emptyList() else body.split(",")
    }

    @Test
    fun `link header rel next is followed across multiple link values`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            val linkHeader =
                "<https://api.example.com/repo/issues?page=2>; rel=\"next\", " +
                    "<https://api.example.com/repo/issues?page=10>; rel=\"last\""
            textResponse(req, "i1,i2", extraHeaders = mapOf("Link" to linkHeader))
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            val linkHeader =
                "<https://api.example.com/repo/issues?page=3>; rel=\"next\", " +
                    "<https://api.example.com/repo/issues?page=1>; rel=\"prev\""
            textResponse(req, "i3,i4", extraHeaders = mapOf("Link" to linkHeader))
        }
        client.on("https://api.example.com/repo/issues?page=3") { req ->
            // No Link header → end of pagination.
            textResponse(req, "i5")
        }

        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), paginator.iterateAll().toList())
        assertEquals(3, client.callCount)
    }

    @Test
    fun `multiple Link header instances are concatenated`() {
        // GitHub historically emitted a single Link header, but some servers (and proxies)
        // split into multiple header lines. The strategy joins them with commas so a single
        // parser handles both wire shapes.
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            val response =
                Response.builder()
                    .request(req)
                    .protocol(org.dexpace.sdk.core.http.common.Protocol.HTTP_1_1)
                    .status(org.dexpace.sdk.core.http.response.Status.OK)
                    .addHeader(
                        "Link",
                        "<https://api.example.com/repo/issues?page=2>; rel=\"next\"",
                    )
                    .addHeader(
                        "Link",
                        "<https://api.example.com/repo/issues?page=99>; rel=\"last\"",
                    )
                    .body(stringBody("only"))
                    .build()
            response
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            textResponse(req, "tail")
        }

        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("only", "tail"), paginator.iterateAll().toList())
        assertEquals(2, client.callCount)
    }

    @Test
    fun `unquoted rel value is supported`() {
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            textResponse(
                req,
                "x",
                extraHeaders =
                    mapOf(
                        "Link" to "<https://api.example.com/repo/issues?page=2>; rel=next",
                    ),
            )
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            textResponse(req, "y")
        }

        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("x", "y"), paginator.iterateAll().toList())
    }

    @Test
    fun `multi-relation rel attribute matches next when listed among others`() {
        // RFC 5988 allows multiple relations in one `rel` parameter, e.g. `rel="next first"`.
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            textResponse(
                req,
                "a",
                extraHeaders =
                    mapOf(
                        "Link" to
                            "<https://api.example.com/repo/issues?page=2>; rel=\"first next\"",
                    ),
            )
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            textResponse(req, "b")
        }
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("a", "b"), paginator.iterateAll().toList())
    }

    @Test
    fun `relative rel next link is resolved against the page URL and paginated`() {
        // RFC 8288 permits relative Link targets; real APIs emit them. The strategy must
        // resolve them against the originating page's response URL, not feed them to a bare
        // single-arg URL(...) (which throws MalformedURLException on a relative reference).
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            textResponse(
                req,
                "i1,i2",
                extraHeaders = mapOf("Link" to "</repo/issues?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            // A relative reference resolved against the page-2 URL resolves the path the same way.
            textResponse(
                req,
                "i3,i4",
                extraHeaders = mapOf("Link" to "</repo/issues?page=3>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/repo/issues?page=3") { req ->
            // No Link header → end of pagination.
            textResponse(req, "i5")
        }

        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), paginator.iterateAll().toList())
        assertEquals(3, client.callCount)
        assertEquals(
            listOf(
                "https://api.example.com/repo/issues",
                "https://api.example.com/repo/issues?page=2",
                "https://api.example.com/repo/issues?page=3",
            ),
            client.receivedUrls,
        )
    }

    @Test
    fun `unparseable next link target ends the stream instead of aborting iteration`() {
        // A next-link target with an unknown scheme cannot be resolved into a valid URL; the
        // strategy must treat it as end-of-stream rather than letting MalformedURLException
        // escape and abort the whole iteration.
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            textResponse(
                req,
                "i1,i2",
                extraHeaders = mapOf("Link" to "<wat://%%%bad>; rel=\"next\""),
            )
        }

        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("i1", "i2"), paginator.iterateAll().toList())
        assertEquals(1, client.callCount)
    }

    @Test
    fun `quoted comma inside parameter value does not split link values`() {
        // A `title="hello, world"` parameter must not cause the parser to split the link value
        // at the embedded comma.
        val client = StubHttpClient()
        client.on("https://api.example.com/repo/issues") { req ->
            val linkHeader =
                "<https://api.example.com/repo/issues?page=2>; " +
                    "title=\"hello, world\"; rel=\"next\""
            textResponse(req, "p", extraHeaders = mapOf("Link" to linkHeader))
        }
        client.on("https://api.example.com/repo/issues?page=2") { req ->
            textResponse(req, "q")
        }
        val strategy = LinkHeaderPaginationStrategy(itemsExtractor)
        val paginator = Paginator(client, initialRequest(), strategy)
        assertEquals(listOf("p", "q"), paginator.iterateAll().toList())
    }

    private fun stringBody(content: String): org.dexpace.sdk.core.http.response.ResponseBody {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return object : org.dexpace.sdk.core.http.response.ResponseBody() {
            private var cached: org.dexpace.sdk.core.io.BufferedSource? = null

            override fun mediaType(): org.dexpace.sdk.core.http.common.MediaType? = null

            override fun contentLength(): Long = bytes.size.toLong()

            override fun source(): org.dexpace.sdk.core.io.BufferedSource =
                cached ?: org.dexpace.sdk.core.io.Io.provider.source(bytes).also { cached = it }

            override fun close() {
                cached?.close()
            }
        }
    }
}
