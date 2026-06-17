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
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsyncPaginatorTest {
    private val itemsExtractor: (Response) -> List<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        if (body.isEmpty()) emptyList() else body.split(",")
    }

    @BeforeTest
    fun setup() {
        installIoProvider()
    }

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

    private fun strategy(): LinkHeaderPaginationStrategy<String> = LinkHeaderPaginationStrategy(itemsExtractor)

    /** A three-page Link-header stub: items -> ?page=2 -> ?page=3 (terminal). */
    private fun threePageClient(executor: java.util.concurrent.Executor? = null): StubAsyncHttpClient {
        val client = StubAsyncHttpClient(executor)
        client.on("https://api.example.com/items") { req ->
            textResponse(
                req,
                "a,b",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/items?page=2") { req ->
            textResponse(
                req,
                "c,d",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=3>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/items?page=3") { req ->
            textResponse(req, "e")
        }
        return client
    }

    @Test
    fun `collectAllAsync walks every page synchronously-completed`() {
        val client = threePageClient()
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val items = paginator.collectAllAsync().get(5, TimeUnit.SECONDS)

        assertEquals(listOf("a", "b", "c", "d", "e"), items)
        assertEquals(3, client.callCount)
        assertEquals(
            listOf(
                "https://api.example.com/items",
                "https://api.example.com/items?page=2",
                "https://api.example.com/items?page=3",
            ),
            client.receivedUrls,
        )
    }

    @Test
    fun `forEachAsync visits each item in order`() {
        val client = threePageClient()
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val seen = ArrayList<String>()
        paginator.forEachAsync { seen.add(it) }.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("a", "b", "c", "d", "e"), seen)
    }

    @Test
    fun `walk completes when pages complete on a background executor`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val client = threePageClient(executor)
            val paginator = AsyncPaginator(client, initialRequest(), strategy())

            val items = paginator.collectAllAsync().get(5, TimeUnit.SECONDS)

            assertEquals(listOf("a", "b", "c", "d", "e"), items)
            assertEquals(3, client.callCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `maxPages caps a server that returns the same cursor forever`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(
                req,
                "x",
                extraHeaders = mapOf("Link" to "<https://api.example.com/items>; rel=\"next\""),
            )
        }
        val paginator = AsyncPaginator(client, initialRequest(), strategy(), maxPages = 3L)

        val items = paginator.collectAllAsync().get(5, TimeUnit.SECONDS)

        assertEquals(listOf("x", "x", "x"), items)
        assertEquals(3, client.callCount)
    }

    @Test
    fun `maxPages of one fetches only the first page`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(
                req,
                "only",
                extraHeaders = mapOf("Link" to "<https://api.example.com/items>; rel=\"next\""),
            )
        }
        val paginator = AsyncPaginator(client, initialRequest(), strategy(), maxPages = 1L)

        assertEquals(listOf("only"), paginator.collectAllAsync().get(5, TimeUnit.SECONDS))
        assertEquals(1, client.callCount)
    }

    @Test
    fun `non-positive maxPages is rejected`() {
        val client = StubAsyncHttpClient()
        assertFailsWith<IllegalArgumentException> {
            AsyncPaginator(client, initialRequest(), strategy(), maxPages = 0L)
        }
    }

    @Test
    fun `transport failure completes the result future exceptionally with the original cause`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { error("boom from transport") }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val ex =
            assertFailsWith<ExecutionException> {
                paginator.collectAllAsync().get(5, TimeUnit.SECONDS)
            }
        // Futures.unwrap unwraps the CompletionException wrapper so callers see the cause.
        assertTrue(ex.cause is IllegalStateException, "cause was ${ex.cause}")
        assertEquals("boom from transport", ex.cause?.message)
    }

    @Test
    fun `strategy parse failure surfaces and still closes the response`() {
        val closed = AtomicInteger(0)
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            countingResponse(req, "a,b", closed)
        }
        // Strategy that always throws while parsing.
        val failing =
            PaginationStrategy<String> { _, _ -> throw IOException("parse exploded") }
        val paginator = AsyncPaginator(client, initialRequest(), failing)

        val ex =
            assertFailsWith<ExecutionException> {
                paginator.collectAllAsync().get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is IOException, "cause was ${ex.cause}")
        assertEquals(1, closed.get(), "response must be closed even when parse throws")
    }

    @Test
    fun `consumer throwing aborts the walk and surfaces the exception`() {
        val client = threePageClient()
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val ex =
            assertFailsWith<ExecutionException> {
                paginator
                    .forEachAsync { item ->
                        if (item == "c") error("stop at c")
                        // else: keep going
                    }.get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is IllegalStateException, "cause was ${ex.cause}")
        assertEquals("stop at c", ex.cause?.message)
        // The walk fetched page 1 (a,b) and page 2 (c,d), then aborted on 'c'. Page 3 untouched.
        assertEquals(2, client.callCount)
    }

    @Test
    fun `each response is closed exactly once after parsing`() {
        val closes = AtomicInteger(0)
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            countingResponse(
                req,
                "a",
                closes,
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/items?page=2") { req ->
            countingResponse(req, "b", closes)
        }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        assertEquals(listOf("a", "b"), paginator.collectAllAsync().get(5, TimeUnit.SECONDS))
        assertEquals(2, closes.get(), "both responses must be closed")
    }

    @Test
    fun `empty terminal page yields no extra items and stops`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(
                req,
                "a,b",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/items?page=2") { req ->
            // Empty body, no Link header → end of stream.
            textResponse(req, "")
        }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        assertEquals(listOf("a", "b"), paginator.collectAllAsync().get(5, TimeUnit.SECONDS))
        assertEquals(2, client.callCount)
    }

    @Test
    fun `deep run of synchronously-completed pages stays stack-safe`() {
        // A server that advances its cursor every page for many pages. With synchronous
        // completion, a naive recursive driver would overflow the stack; the trampoline must
        // process these in a loop.
        val pageCount = 5_000
        val client = StubAsyncHttpClient()
        for (page in 0 until pageCount) {
            val url =
                if (page == 0) {
                    "https://api.example.com/items"
                } else {
                    "https://api.example.com/items?page=$page"
                }
            val isLast = page == pageCount - 1
            client.on(url) { req ->
                if (isLast) {
                    textResponse(req, "p$page")
                } else {
                    textResponse(
                        req,
                        "p$page",
                        extraHeaders =
                            mapOf(
                                "Link" to
                                    "<https://api.example.com/items?page=${page + 1}>; rel=\"next\"",
                            ),
                    )
                }
            }
        }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val items = paginator.collectAllAsync().get(30, TimeUnit.SECONDS)
        assertEquals(pageCount, items.size)
        assertEquals("p0", items.first())
        assertEquals("p${pageCount - 1}", items.last())
    }

    @Test
    fun `collectAllAsync wrapper rethrows as CompletionException on join`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { error("kaboom") }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        // join() (vs get()) wraps in CompletionException — confirms the failure path is wired
        // through the standard CompletableFuture contract.
        assertFailsWith<CompletionException> {
            paginator.collectAllAsync().join()
        }
    }

    @AfterTest
    fun teardown() {
        // no-op
    }
}

/**
 * A [textResponse] whose [Response.close] increments [closeCounter], letting tests assert the
 * paginator closes each response exactly once.
 */
private fun countingResponse(
    request: Request,
    body: String,
    closeCounter: AtomicInteger,
    extraHeaders: Map<String, String> = emptyMap(),
): Response {
    val delegate = textResponse(request, body, extraHeaders)
    val countingBody =
        object : org.dexpace.sdk.core.http.response.ResponseBody() {
            override fun mediaType(): org.dexpace.sdk.core.http.common.MediaType? = delegate.body?.mediaType()

            override fun contentLength(): Long = delegate.body?.contentLength() ?: -1L

            override fun source(): org.dexpace.sdk.core.io.BufferedSource = delegate.body!!.source()

            override fun close() {
                closeCounter.incrementAndGet()
                delegate.close()
            }
        }
    return delegate.newBuilder().body(countingBody).build()
}
