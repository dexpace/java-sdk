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
import java.io.UncheckedIOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Response-close coverage for the sync [Paginator] that the async mirror already has: the
 * parse-failure release branch, the page-level early-break release, and the stream short-circuit
 * regression (the held page is released when a short-circuited page stream is closed).
 */
class PaginatorCloseTest {
    @BeforeTest fun setup() = installIoProvider()

    private fun initialRequest(): Request =
        Request.builder()
            .url("https://api.example.com/items")
            .method(Method.GET)
            .build()

    private val extractor: (Response) -> CursorResult<String> = { resp ->
        val body = resp.body!!.source().use { it.readUtf8() }
        val itemsLine = body.lineSequence().firstOrNull { it.startsWith("items=") } ?: "items="
        val cursorLine = body.lineSequence().firstOrNull { it.startsWith("cursor=") } ?: "cursor="
        val itemsRaw = itemsLine.removePrefix("items=")
        val cursorRaw = cursorLine.removePrefix("cursor=")
        val items = if (itemsRaw.isEmpty()) emptyList() else itemsRaw.split(",")
        CursorResult(items, cursorRaw.ifEmpty { null })
    }

    /** Two-page cursor stub whose page responses record their close count into [closes]. */
    private fun twoPageClient(closes: AtomicInteger): StubHttpClient =
        StubHttpClient()
            .on("https://api.example.com/items") { req ->
                closeRecordingResponse(req, closes, "items=a,b\ncursor=abc")
            }
            .on("https://api.example.com/items?cursor=abc") { req ->
                closeRecordingResponse(req, closes, "items=c\ncursor=")
            }

    @Test
    fun `sync strategy parse failure surfaces and still closes the response`() {
        // The async mirror is tested twice, but no sync test drove a throwing strategy — deleting
        // the sync walker() response.close() would strand a connection on every sync parse failure
        // and leave the build green. A throwing strategy must propagate AND release the response.
        val closes = AtomicInteger(0)
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req -> closeRecordingResponse(req, closes, "items=a,b") }
        val failing = PaginationStrategy<String> { _, _ -> throw IOException("parse exploded") }
        val paginator = Paginator(client, initialRequest(), failing)

        val ex =
            assertFailsWith<IOException> {
                paginator.iterateAll().iterator().hasNext()
            }
        assertEquals("parse exploded", ex.message)
        assertEquals(1, closes.get(), "response must be closed even when parse throws")
    }

    @Test
    fun `sync parse failure attaches a throwing close as suppressed and does not mask the parse error`() {
        // When releasing the page whose parse failed, a close() that itself throws must not replace
        // the parse failure the caller needs to see — it is attached as suppressed instead.
        val client = StubHttpClient()
        client.on("https://api.example.com/items") { req -> closeFailingResponse(req, "items=a,b", "close exploded") }
        val failing = PaginationStrategy<String> { _, _ -> throw IOException("parse exploded") }
        val paginator = Paginator(client, initialRequest(), failing)

        val ex =
            assertFailsWith<IOException> {
                paginator.iterateAll().iterator().hasNext()
            }
        assertEquals("parse exploded", ex.message, "the parse failure must propagate, not the close failure")
        val suppressed = ex.suppressed.toList()
        assertEquals(1, suppressed.size, "the close failure must be attached as suppressed, not swallowed or masking")
        assertEquals("close exploded", suppressed.first().message)
    }

    @Test
    fun `byPage stream rethrows a failing page close as UncheckedIOException on stream close`() {
        // Stream.close() declares no checked exception, so a held page whose close() throws IOException
        // must surface as an UncheckedIOException (not a sneaky-thrown, uncatchable-by-type checked one).
        val client =
            StubHttpClient().on("https://api.example.com/items") { req ->
                closeFailingResponse(req, "items=a,b\ncursor=abc", "held page close exploded")
            }
        val paginator = Paginator(client, initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val stream = paginator.byPage().stream()
        stream.findFirst() // pulls page 1, leaves it held open
        val ex = assertFailsWith<UncheckedIOException> { stream.close() }
        assertEquals("held page close exploded", ex.cause?.message, "the original IOException must be the cause")
    }

    @Test
    fun `byPage early break without use leaves one page open and close releases it`() {
        // Sync mirror of PagedIterableTest's early-break case, but over a live-Response client:
        // breaking while page 2 is current leaves it open; view.close() releases it and is idempotent.
        val closes = AtomicInteger(0)
        val paginator =
            Paginator(twoPageClient(closes), initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val view = paginator.byPage()
        val pages = view.iterator()
        var advanced = 0
        while (pages.hasNext()) {
            pages.next()
            advanced++
            if (advanced == 2) break // break while page 2 is still current/open
        }
        assertEquals(1, closes.get(), "after breaking on page 2 only page 1 (advanced past) is closed")

        view.close()
        assertEquals(2, closes.get(), "close() must release the still-held page 2")

        view.close()
        assertEquals(2, closes.get(), "close() is idempotent — no double-close")
    }

    @Test
    fun `byPage stream short-circuit releases the held page when the stream is closed`() {
        // Regression: a short-circuiting terminal (findFirst) pulls one page and abandons the stream
        // without exhausting it. With the stream's onClose wired to the view, closing the stream
        // (try-with-resources / use) releases the page left held — no stranded connection.
        val closes = AtomicInteger(0)
        val paginator =
            Paginator(twoPageClient(closes), initialRequest(), CursorPaginationStrategy(extractor, "cursor"))

        val first: Page<String> =
            paginator.byPage().stream().use { pages ->
                pages.findFirst().orElseThrow { AssertionError("expected at least one page") }
            }

        assertEquals(listOf("a", "b"), first.items, "materialized items survive the page close")
        assertEquals(1, closes.get(), "closing the short-circuited stream must release the held page")
    }
}
