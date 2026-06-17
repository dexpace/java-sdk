/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SseStreamTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun source(text: String): BufferedSource = Io.provider.source(text.toByteArray(Charsets.UTF_8))

    /** Records how many times close() ran, standing in for a Response/body. */
    private class CountingCloseable : Closeable {
        val closeCount = AtomicInteger(0)

        override fun close() {
            closeCount.incrementAndGet()
        }

        val isClosed: Boolean get() = closeCount.get() > 0
    }

    @Test
    fun `full iteration yields all events and auto-closes the resource`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(source("data: a\n\ndata: b\n\n"), resource)

        val events = stream.toList()

        assertEquals(2, events.size)
        assertEquals(listOf("a"), events[0].data)
        assertEquals(listOf("b"), events[1].data)
        // Clean end-of-stream closes the resource exactly once without an explicit close().
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `explicit close propagates to the resource`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(source("data: a\n\n"), resource)

        assertFalse(resource.isClosed)
        stream.close()
        assertTrue(resource.isClosed)
    }

    @Test
    fun `use block closes the resource even on partial consume`() {
        val resource = CountingCloseable()
        val first =
            SseStream.from(source("data: a\n\ndata: b\n\ndata: c\n\n"), resource).use { stream ->
                // Pull only the first event, then leave the use block — the rest are abandoned.
                stream.iterator().next()
            }

        assertEquals(listOf("a"), first.data)
        // Partial consume must not strand the resource: use{} closed it.
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `close is idempotent`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(source("data: a\n\n"), resource)

        stream.close()
        stream.close()
        stream.close()

        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `iterator may only be taken once`() {
        val stream = SseStream.from(source("data: a\n\n"), CountingCloseable())

        stream.iterator()
        assertFailsWith<IllegalStateException> { stream.iterator() }
    }

    @Test
    fun `iterator on a closed stream throws`() {
        val stream = SseStream.from(source("data: a\n\n"), CountingCloseable())
        stream.close()

        assertFailsWith<IllegalStateException> { stream.iterator() }
    }

    @Test
    fun `out-of-band close ends an in-flight iteration cleanly`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(source("data: a\n\ndata: b\n\ndata: c\n\n"), resource)
        val iter = stream.iterator()

        // Consume the first event, then close mid-iteration (e.g. cancellation).
        assertTrue(iter.hasNext())
        assertEquals(listOf("a"), iter.next().data)
        stream.close()

        // The next pull observes the closed state and terminates without error.
        assertFalse(iter.hasNext())
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `reader exception propagates and leaves the resource closeable`() {
        val resource = CountingCloseable()
        val goodPart = "data: good\n\n".toByteArray(Charsets.UTF_8)
        val backing = Io.provider.buffer().also { it.write(goodPart) }
        val failingSource =
            object : BufferedSource by backing {
                override fun exhausted(): Boolean = false

                override fun readByte(): Byte {
                    if (backing.size == 0L) throw IOException("simulated connection drop")
                    return backing.readByte()
                }

                override fun peek(): BufferedSource = backing.peek()
            }

        val stream = SseStream.from(failingSource, resource)
        val iter = stream.iterator()

        assertEquals(listOf("good"), iter.next().data)
        // The mid-stream drop surfaces on the next pull.
        assertFailsWith<IOException> { iter.hasNext() }
        // The stream stayed open after the failure, so the caller can still release it.
        stream.close()
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `Response sseStream binds the stream to the response body lifecycle`() {
        val closed = AtomicInteger(0)
        val backing = source("data: a\n\ndata: b\n\n")
        val body =
            object : ResponseBody() {
                override fun mediaType() = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = backing

                override fun close() {
                    closed.incrementAndGet()
                    backing.close()
                }
            }
        val response =
            Response.builder()
                .request(Request.builder().method(Method.GET).url("https://example.test/stream").build())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .body(body)
                .build()

        val events = response.sseStream().use { it.toList() }

        assertEquals(2, events.size)
        // Closing the SSE stream closed the response body.
        assertEquals(1, closed.get())
    }

    @Test
    fun `Response sseStream throws when the response has no body`() {
        val response =
            Response.builder()
                .request(Request.builder().method(Method.GET).url("https://example.test/stream").build())
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build()

        assertFailsWith<IllegalStateException> { response.sseStream() }
    }

    @Test
    fun `fromReader binds an existing reader to a resource`() {
        val resource = CountingCloseable()
        val reader = ServerSentEventReader(source("data: x\n\n"))
        val stream = SseStream.fromReader(reader, resource)

        val events = stream.toList()
        assertEquals(listOf("x"), events.single().data)
        assertEquals(1, resource.closeCount.get())
    }
}
