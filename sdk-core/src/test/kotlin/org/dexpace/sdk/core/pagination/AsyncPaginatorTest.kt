/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.io.BufferedSource
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private fun threePageClient(executor: Executor? = null): StubAsyncHttpClient {
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

    @Test
    fun `cancelling the result future aborts the in-flight exchange and stops the walk`() {
        // A transport that hands back a future which never completes on its own, so the walk
        // suspends on the first page until the test cancels it.
        val transportFuture = CompletableFuture<Response>()
        val callCount = AtomicInteger(0)
        val client =
            AsyncHttpClient {
                callCount.incrementAndGet()
                transportFuture
            }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val result = paginator.forEachAsync { }
        assertFalse(result.isDone, "walk should be suspended on the in-flight page")

        result.cancel(true)

        assertTrue(transportFuture.isCancelled, "in-flight transport future must be cancelled")
        assertTrue(result.isCancelled)
        assertEquals(1, callCount.get(), "no further pages fetched after cancellation")
    }

    @Test
    fun `completing the result during the walk stops it before the next fetch`() {
        // Page 1 is held by a future the test completes by hand; page 2 records whether it is
        // ever fetched. The consumer completes the result future from inside the walk (on page 1's
        // last item), which must make the driver terminate at the next fetch decision — exercising
        // the result.isDone short-circuit in step(), not just the cancel hook.
        val page1 = CompletableFuture<Response>()
        val page2Calls = AtomicInteger(0)
        val client =
            AsyncHttpClient { req ->
                if (req.url.toString() == "https://api.example.com/items") {
                    page1
                } else {
                    page2Calls.incrementAndGet()
                    CompletableFuture.completedFuture(textResponse(req, "c,d"))
                }
            }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val resultRef = AtomicReference<CompletableFuture<Void>>()
        val seen = ArrayList<String>()
        val result =
            paginator.forEachAsync { item ->
                seen.add(item)
                if (item == "b") resultRef.get().complete(null)
            }
        resultRef.set(result)

        // Walk is suspended on page 1; nothing consumed yet.
        assertTrue(seen.isEmpty(), "consumer should not run before page 1 completes")

        // Completing page 1 drains "a","b" inline; the consumer settles the result on "b".
        page1.complete(
            textResponse(
                initialRequest(),
                "a,b",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=2>; rel=\"next\""),
            ),
        )

        result.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("a", "b"), seen)
        assertEquals(0, page2Calls.get(), "page 2 must not be fetched after the result is settled")
    }

    @Test
    fun `a null Response completion fails the walk cleanly`() {
        // A misbehaving transport that violates the AsyncHttpClient contract by completing with a
        // null Response. The paginator must fail the walk rather than NPE inside parse/close.
        @Suppress("UNCHECKED_CAST")
        val client =
            AsyncHttpClient {
                CompletableFuture.completedFuture<Response?>(null) as CompletableFuture<Response>
            }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val ex =
            assertFailsWith<ExecutionException> {
                paginator.collectAllAsync().get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is IllegalStateException, "cause was ${ex.cause}")
    }

    @Test
    fun `a parse failure on a subsequent page surfaces through the result future`() {
        val client = StubAsyncHttpClient()
        client.on("https://api.example.com/items") { req ->
            textResponse(
                req,
                "a,b",
                extraHeaders =
                    mapOf("Link" to "<https://api.example.com/items?page=2>; rel=\"next\""),
            )
        }
        client.on("https://api.example.com/items?page=2") { req -> textResponse(req, "c") }
        // A strategy that succeeds on page 1 but throws from parse() on page 2.
        val delegate = strategy()
        var callCount = 0
        val erroring =
            PaginationStrategy<String> { response, request ->
                if (++callCount > 1) error("cannot parse subsequent page")
                delegate.parse(response, request)
            }
        val paginator = AsyncPaginator(client, initialRequest(), erroring)

        val ex =
            assertFailsWith<ExecutionException> {
                paginator.collectAllAsync().get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is IllegalStateException, "cause was ${ex.cause}")
        assertEquals("cannot parse subsequent page", ex.cause?.message)
    }

    @Test
    fun `an eager throw from executeAsync fails the walk instead of escaping`() {
        // Unlike StubAsyncHttpClient (which turns a responder throw into a failed future), this
        // transport violates the contract by throwing synchronously out of executeAsync. The
        // paginator must catch it and fail the walk rather than let it escape forEachAsync.
        val client = AsyncHttpClient { throw IllegalStateException("eager executeAsync failure") }
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val ex =
            assertFailsWith<ExecutionException> {
                paginator.collectAllAsync().get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is IllegalStateException, "cause was ${ex.cause}")
        assertEquals("eager executeAsync failure", ex.cause?.message)
    }

    @Test
    fun `forEachAsync with an executor runs the consumer on that executor`() {
        // Synchronous-completion transport: without the executor the consumer would run on the
        // caller's thread. With it, even synchronously completed pages must drain on the executor.
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "consumer") }
        try {
            val client = threePageClient()
            val paginator = AsyncPaginator(client, initialRequest(), strategy())

            val threads = ConcurrentHashMap.newKeySet<String>()
            val seen = ArrayList<String>()
            val recorder =
                Consumer<String> { item ->
                    threads.add(Thread.currentThread().name)
                    seen.add(item)
                }
            paginator.forEachAsync(recorder, executor).get(5, TimeUnit.SECONDS)

            assertEquals(listOf("a", "b", "c", "d", "e"), seen)
            assertEquals(setOf("consumer"), threads, "consumer must run only on the supplied executor")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `executor overload isolates the consumer from the transport callback thread`() {
        // Pages complete on a background "transport" pool; the consumer is pinned to its own
        // "consumer" executor. The consumer must never observe a transport thread.
        val transport = Executors.newFixedThreadPool(2) { r -> Thread(r, "transport") }
        val consumerExec = Executors.newSingleThreadExecutor { r -> Thread(r, "consumer") }
        try {
            val client = threePageClient(transport)
            val paginator = AsyncPaginator(client, initialRequest(), strategy())

            val threads = ConcurrentHashMap.newKeySet<String>()
            val recorder = Consumer<String> { threads.add(Thread.currentThread().name) }
            paginator.forEachAsync(recorder, consumerExec).get(5, TimeUnit.SECONDS)

            assertEquals(setOf("consumer"), threads, "consumer ran on a transport thread")
        } finally {
            transport.shutdownNow()
            consumerExec.shutdownNow()
        }
    }

    @Test
    fun `collectAllAsync with an executor collects every item in order`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = threePageClient()
            val paginator = AsyncPaginator(client, initialRequest(), strategy())

            val items = paginator.collectAllAsync(executor).get(5, TimeUnit.SECONDS)

            assertEquals(listOf("a", "b", "c", "d", "e"), items)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `deep run on an executor stays stack-safe`() {
        // The trampoline must stay flat even when entered through an executor: a long run of
        // synchronously completed pages is processed in a single drive loop on one executor task.
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
        val executor = Executors.newSingleThreadExecutor()
        try {
            val paginator = AsyncPaginator(client, initialRequest(), strategy())
            val items = paginator.collectAllAsync(executor).get(30, TimeUnit.SECONDS)
            assertEquals(pageCount, items.size)
            assertEquals("p${pageCount - 1}", items.last())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a rejected executor dispatch fails the walk`() {
        // A shut-down executor rejects the very first dispatch; the walk must complete
        // exceptionally with the rejection rather than hang or leak it.
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdownNow()
        val client = threePageClient()
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val noop = Consumer<String> { }
        val ex =
            assertFailsWith<ExecutionException> {
                paginator.forEachAsync(noop, executor).get(5, TimeUnit.SECONDS)
            }
        assertTrue(ex.cause is RejectedExecutionException, "cause was ${ex.cause}")
    }

    @Test
    fun `forEachPageAsync delivers rich pages in order`() {
        val client = threePageClient()
        val paginator = AsyncPaginator(client, initialRequest(), strategy())

        val collectedPages = java.util.concurrent.CopyOnWriteArrayList<Page<String>>()
        paginator.forEachPageAsync { page -> collectedPages.add(page) }.join()

        assertEquals(3, collectedPages.size)
        assertEquals(listOf("a", "b"), collectedPages[0].items)
        assertEquals(listOf("c", "d"), collectedPages[1].items)
        assertEquals(listOf("e"), collectedPages[2].items)
        assertTrue(collectedPages.all { it.statusCode == 200 }, "all pages must carry HTTP 200")
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
        object : ResponseBody() {
            override fun mediaType(): MediaType? = delegate.body?.mediaType()

            override fun contentLength(): Long = delegate.body?.contentLength() ?: -1L

            override fun source(): BufferedSource = delegate.body!!.source()

            override fun close() {
                closeCounter.incrementAndGet()
                delegate.close()
            }
        }
    return delegate.newBuilder().body(countingBody).build()
}
