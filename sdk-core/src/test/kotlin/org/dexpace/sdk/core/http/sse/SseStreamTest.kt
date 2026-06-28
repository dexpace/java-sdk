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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * A source that replays [text] and then throws on the next read, standing in for a transport
     * whose connection drops mid-stream.
     */
    private fun failingSourceAfter(text: String): BufferedSource {
        val backing = Io.provider.buffer().also { it.write(text.toByteArray(Charsets.UTF_8)) }
        return object : BufferedSource by backing {
            override fun exhausted(): Boolean = false

            override fun readByte(): Byte {
                if (backing.size == 0L) throw IOException("simulated connection drop")
                return backing.readByte()
            }

            override fun peek(): BufferedSource = backing.peek()
        }
    }

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
    fun `mid-stream reader failure propagates and releases the resource`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(failingSourceAfter("data: good\n\n"), resource)
        val iter = stream.iterator()

        assertEquals(listOf("good"), iter.next().data)
        // The mid-stream drop surfaces on the next pull...
        assertFailsWith<IOException> { iter.hasNext() }
        // ...and releases the response on the way out, so a consumer iterating without use{}
        // never strands the connection.
        assertEquals(1, resource.closeCount.get())
        // close() remains a safe, idempotent no-op after the automatic release.
        stream.close()
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `toList propagates a mid-stream failure but still releases the resource`() {
        val resource = CountingCloseable()
        val stream = SseStream.from(failingSourceAfter("data: good\n\n"), resource)

        // A bare terminal operation (no use{}) still releases the response on a mid-stream drop.
        assertFailsWith<IOException> { stream.toList() }
        assertEquals(1, resource.closeCount.get())
    }

    @Test
    fun `a resource close failure on clean end-of-stream does not discard already-read events`() {
        val stream =
            SseStream.from(source("data: a\n\ndata: b\n\n"), Closeable { throw IOException("release failed") })

        // Every event was read successfully; a failure to release the connection on the terminal
        // pull must not turn that into a thrown result and lose the collected events.
        val events = stream.toList()

        assertEquals(2, events.size)
        assertEquals(listOf("a"), events[0].data)
        assertEquals(listOf("b"), events[1].data)
    }

    @Test
    fun `explicit close surfaces a resource close failure`() {
        val stream = SseStream.from(source("data: a\n\n"), Closeable { throw IOException("release failed") })

        // Unlike automatic end-of-stream cleanup, an explicit close() is the caller asking to
        // release, so a release failure is propagated to them.
        assertFailsWith<IOException> { stream.close() }
    }

    @Test
    fun `close during a blocked read cancels the iteration via an IOException`() {
        val readStarted = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val resource =
            Closeable {
                released.set(true)
                releaseLatch.countDown()
            }
        // A source that blocks inside readByte() until the resource is closed, then fails the way a
        // real transport does when its socket is closed out from under an in-flight read.
        val blockingSource =
            object : BufferedSource by Io.provider.buffer() {
                override fun exhausted(): Boolean = false

                // Empty peek => the reader's BOM probe finds no BOM and does not block here.
                override fun peek(): BufferedSource = Io.provider.buffer()

                override fun readByte(): Byte {
                    readStarted.countDown()
                    releaseLatch.await(2, TimeUnit.SECONDS)
                    throw IOException("source closed during read")
                }
            }
        val stream = SseStream.from(blockingSource, resource)
        val iter = stream.iterator()
        val failure = AtomicReference<Throwable?>()
        val readerThread =
            Thread {
                try {
                    iter.hasNext()
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        readerThread.start()

        // Wait until the iterating thread is parked inside the blocking read, then cancel.
        assertTrue(readStarted.await(2, TimeUnit.SECONDS))
        stream.close()
        readerThread.join(TimeUnit.SECONDS.toMillis(2))

        // Cancelling an in-flight read surfaces as an IOException to the iterating thread (not a
        // clean end), and the resource is released exactly once.
        assertTrue(failure.get() is IOException, "expected IOException, got ${failure.get()}")
        assertTrue(released.get())
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
