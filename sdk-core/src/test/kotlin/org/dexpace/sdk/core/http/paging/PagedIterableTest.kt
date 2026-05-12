package org.dexpace.sdk.core.http.paging

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PagedIterableTest {

    private lateinit var request: Request

    @BeforeTest
    fun setUp() {
        request = Request.builder()
            .url("https://api.example.test/items")
            .method(Method.GET)
            .build()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun emptyResponse(): Response = Response.builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .status(Status.OK)
        .build()

    private fun <T> page(
        value: List<T>,
        nextLink: String? = null,
        continuationToken: String? = null,
        previousLink: String? = null,
        firstLink: String? = null,
        lastLink: String? = null,
        response: Response = emptyResponse(),
    ): PagedResponse<T> = PagedResponse(
        response = response,
        value = value,
        continuationToken = continuationToken,
        nextLink = nextLink,
        previousLink = previousLink,
        firstLink = firstLink,
        lastLink = lastLink,
    )

    // ---------------------------------------------------------------------
    // Basic iteration
    // ---------------------------------------------------------------------

    @Test
    fun `single page with no nextLink yields all items`() {
        val iterable = PagedIterable<Int>(firstPage = { page(listOf(1, 2, 3)) })
        assertEquals(listOf(1, 2, 3), iterable.toList())
    }

    @Test
    fun `two pages chained via nextLink combine in order`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2, 3), nextLink = "p2") },
            nextPage = { _, link ->
                assertEquals("p2", link)
                page(listOf(4, 5, 6))
            },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), iterable.toList())
    }

    @Test
    fun `byPage returns each page in order without flattening`() {
        val p1 = page(listOf(10, 20), nextLink = "p2")
        val p2 = page(listOf(30, 40))
        val iterable = PagedIterable<Int>(
            firstPage = { p1 },
            nextPage = { _, _ -> p2 },
        )
        val pages = iterable.byPage().toList()
        assertEquals(2, pages.size)
        assertSame(p1, pages[0])
        assertSame(p2, pages[1])
        assertEquals(listOf(10, 20), pages[0].value)
        assertEquals(listOf(30, 40), pages[1].value)
    }

    // ---------------------------------------------------------------------
    // Empty / null edge cases
    // ---------------------------------------------------------------------

    @Test
    fun `empty first page with no nextLink yields nothing`() {
        val iterable = PagedIterable<Int>(firstPage = { page(emptyList()) })
        assertEquals(emptyList(), iterable.toList())
        assertFalse(iterable.iterator().hasNext())
    }

    @Test
    fun `null first page yields nothing`() {
        val iterable = PagedIterable<Int>(firstPage = { null })
        assertEquals(emptyList(), iterable.toList())
    }

    @Test
    fun `empty page with non-null nextLink continues to next page`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(emptyList(), nextLink = "p2") },
            nextPage = { _, link ->
                assertEquals("p2", link)
                page(listOf(1, 2))
            },
        )
        assertEquals(listOf(1, 2), iterable.toList())
    }

    @Test
    fun `continuationToken used when nextLink is null`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1), continuationToken = "tok") },
            nextPage = { _, link ->
                assertEquals("tok", link)
                page(listOf(2))
            },
        )
        assertEquals(listOf(1, 2), iterable.toList())
    }

    @Test
    fun `nextLink wins when both nextLink and continuationToken are set`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1), nextLink = "link", continuationToken = "tok") },
            nextPage = { _, link ->
                assertEquals("link", link, "nextLink should take precedence over continuationToken")
                page(listOf(2))
            },
        )
        assertEquals(listOf(1, 2), iterable.toList())
    }

    @Test
    fun `empty string nextLink terminates iteration`() {
        val nextPageCalled = AtomicInteger(0)
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2), nextLink = "") },
            nextPage = { _, _ ->
                nextPageCalled.incrementAndGet()
                page(listOf(99))
            },
        )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(0, nextPageCalled.get(), "nextPage should not be called for empty nextLink")
    }

    @Test
    fun `empty string continuationToken terminates iteration`() {
        val nextPageCalled = AtomicInteger(0)
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2), continuationToken = "") },
            nextPage = { _, _ ->
                nextPageCalled.incrementAndGet()
                page(listOf(99))
            },
        )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(0, nextPageCalled.get(), "nextPage should not be called for empty continuationToken")
    }

    @Test
    fun `null nextPage result terminates iteration`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1), nextLink = "p2") },
            nextPage = { _, _ -> null },
        )
        assertEquals(listOf(1), iterable.toList())
    }

    // ---------------------------------------------------------------------
    // Iterator contract
    // ---------------------------------------------------------------------

    @Test
    fun `iterator called multiple times starts fresh`() {
        val firstPageCalls = AtomicInteger(0)
        val iterable = PagedIterable<Int>(firstPage = {
            firstPageCalls.incrementAndGet()
            page(listOf(1, 2, 3))
        })

        assertEquals(listOf(1, 2, 3), iterable.toList())
        assertEquals(listOf(1, 2, 3), iterable.toList())
        assertEquals(2, firstPageCalls.get(), "firstPage should be re-invoked for each iteration")
    }

    @Test
    fun `iterator short-circuits when consumer stops pulling`() {
        val firstPageCalls = AtomicInteger(0)
        val nextPageCalls = AtomicInteger(0)
        val iterable = PagedIterable<Int>(
            firstPage = {
                firstPageCalls.incrementAndGet()
                page(listOf(1, 2, 3), nextLink = "p2")
            },
            nextPage = { _, _ ->
                nextPageCalls.incrementAndGet()
                page(listOf(4, 5, 6))
            },
        )

        // Take only the first two items — never advance past page 1.
        val first = iterable.take(2).toList()
        assertEquals(listOf(1, 2), first)
        assertEquals(1, firstPageCalls.get())
        assertEquals(0, nextPageCalls.get(), "nextPage must not run when consumer stops mid-page")
    }

    // ---------------------------------------------------------------------
    // maxPages safety cap
    // ---------------------------------------------------------------------

    @Test
    fun `maxPages caps iteration when server returns the same nextLink repeatedly`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1), nextLink = "same") },
            nextPage = { _, _ ->
                nextPageCalls.incrementAndGet()
                page(listOf(2), nextLink = "same")
            },
            maxPages = 3,
        )

        // 3 pages total: first + 2 nextPage calls. Items: [1, 2, 2].
        assertEquals(listOf(1, 2, 2), iterable.toList())
        assertEquals(2, nextPageCalls.get(), "nextPage called twice to fill the cap of 3 pages")
    }

    @Test
    fun `maxPages of 1 stops after the first page`() {
        val nextPageCalls = AtomicInteger(0)
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2), nextLink = "p2") },
            nextPage = { _, _ ->
                nextPageCalls.incrementAndGet()
                page(listOf(3, 4))
            },
            maxPages = 1,
        )
        assertEquals(listOf(1, 2), iterable.toList())
        assertEquals(0, nextPageCalls.get())
    }

    // ---------------------------------------------------------------------
    // Stream interop
    // ---------------------------------------------------------------------

    @Test
    fun `stream returns same contents as iterator`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2), nextLink = "p2") },
            nextPage = { _, _ -> page(listOf(3, 4)) },
        )

        val streamed: List<Int> = iterable.stream().collect(Collectors.toList())
        assertEquals(listOf(1, 2, 3, 4), streamed)
    }

    @Test
    fun `stream is independent across calls`() {
        val firstPageCalls = AtomicInteger(0)
        val iterable = PagedIterable<Int>(firstPage = {
            firstPageCalls.incrementAndGet()
            page(listOf(1, 2, 3))
        })

        val s1: List<Int> = iterable.stream().collect(Collectors.toList())
        val s2: List<Int> = iterable.stream().collect(Collectors.toList())
        assertEquals(listOf(1, 2, 3), s1)
        assertEquals(listOf(1, 2, 3), s2)
        assertEquals(2, firstPageCalls.get())
    }

    // ---------------------------------------------------------------------
    // Error propagation
    // ---------------------------------------------------------------------

    @Test
    fun `network failure in firstPage propagates from iterator advance`() {
        val iterable = PagedIterable<Int>(firstPage = {
            throw IOException("dns failure")
        })

        val iterator = iterable.iterator()
        val ex = assertFailsWith<IOException> { iterator.hasNext() }
        assertEquals("dns failure", ex.message)
    }

    @Test
    fun `network failure in nextPage propagates after first page is drained`() {
        val iterable = PagedIterable<Int>(
            firstPage = { page(listOf(1, 2), nextLink = "p2") },
            nextPage = { _, _ -> throw IOException("404 on next page") },
        )

        val iterator = iterable.iterator()
        // First two items come from page 1 — no error yet.
        assertTrue(iterator.hasNext())
        assertEquals(1, iterator.next())
        assertEquals(2, iterator.next())
        // Pulling further forces the nextPage call, which throws.
        val ex = assertFailsWith<IOException> { iterator.hasNext() }
        assertEquals("404 on next page", ex.message)
    }

    // ---------------------------------------------------------------------
    // PagedResponse.close()
    // ---------------------------------------------------------------------

    @Test
    fun `PagedResponse close delegates to underlying Response`() {
        // Build a response whose body's close() flips a flag. The body overrides close()
        // entirely (does NOT call super) — Response.close() calls body.close(), which is
        // what we want to observe.
        val closed = AtomicInteger(0)
        val body = object : org.dexpace.sdk.core.http.response.ResponseBody() {
            override fun mediaType() = null
            override fun contentLength() = -1L
            override fun source(): org.dexpace.sdk.core.io.BufferedSource =
                throw UnsupportedOperationException("not needed for close test")

            override fun close() {
                closed.incrementAndGet()
            }
        }
        val response = Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .body(body)
            .build()
        val paged = PagedResponse(
            response = response,
            value = listOf(1, 2, 3),
        )

        assertEquals(Status.OK.code, paged.statusCode)
        assertSame(response.headers, paged.headers)
        assertSame(request, paged.request)

        paged.close()
        assertEquals(1, closed.get(), "underlying body.close() should have been called once")
    }

    // ---------------------------------------------------------------------
    // PagingOptions propagation
    // ---------------------------------------------------------------------

    @Test
    fun `byPage forwards the supplied PagingOptions to firstPage and nextPage`() {
        val seenOptionsFirst = arrayOfNulls<PagingOptions>(1)
        val seenOptionsNext = arrayOfNulls<PagingOptions>(1)
        val iterable = PagedIterable<Int>(
            firstPage = { opts ->
                seenOptionsFirst[0] = opts
                page(listOf(1), nextLink = "p2")
            },
            nextPage = { opts, _ ->
                seenOptionsNext[0] = opts
                page(listOf(2))
            },
        )

        val opts = PagingOptions(offset = 10, pageSize = 5, pageIndex = 2, continuationToken = "tok")
        assertEquals(listOf(1, 2), iterable.byPage(opts).flatMap { it.value }.toList())
        assertSame(opts, seenOptionsFirst[0])
        assertSame(opts, seenOptionsNext[0])
    }

    @Test
    fun `PagingOptions defaults are all null`() {
        val opts = PagingOptions()
        assertEquals(null, opts.offset)
        assertEquals(null, opts.pageSize)
        assertEquals(null, opts.pageIndex)
        assertEquals(null, opts.continuationToken)
    }

    @Test
    fun `PagingOptions fields are mutable`() {
        val opts = PagingOptions()
        opts.offset = 100L
        opts.pageSize = 25L
        opts.pageIndex = 4L
        opts.continuationToken = "next"

        assertEquals(100L, opts.offset)
        assertEquals(25L, opts.pageSize)
        assertEquals(4L, opts.pageIndex)
        assertEquals("next", opts.continuationToken)
    }
}
