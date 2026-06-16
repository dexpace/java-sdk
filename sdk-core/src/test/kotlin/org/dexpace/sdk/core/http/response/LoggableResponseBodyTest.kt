/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggableResponseBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    // ----- helpers -----

    private fun bodyFromText(
        text: String,
        closeCount: AtomicInteger? = null,
    ): ResponseBody {
        return object : ResponseBody() {
            override fun mediaType(): MediaType? = MediaType.parse("text/plain")

            override fun contentLength(): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

            override fun source(): BufferedSource = Io.provider.buffer().also { it.writeUtf8(text) }

            override fun close() {
                closeCount?.incrementAndGet()
            }
        }
    }

    // ----- source() that throws before entering .use {} -----

    @Test
    fun `when delegate source() itself throws close() still closes the delegate`() {
        // If delegate.source() throws, the source was never opened, so the delegate
        // is NOT marked closed. A subsequent wrapper.close() must still close the delegate.
        val delegateCloseCount = AtomicInteger(0)
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = throw IOException("connection refused")

                override fun close() {
                    delegateCloseCount.incrementAndGet()
                }
            }

        val wrapper = LoggableResponseBody(delegate)
        assertFailsWith<IOException> { wrapper.source() }

        wrapper.close()

        assertEquals(
            1,
            delegateCloseCount.get(),
            "delegate.close() must be called exactly once when source() threw before drain started",
        )
    }

    // ----- existing regression: drain failure during read -----

    @Test
    fun `close after drain failure does not double-close the delegate`() {
        // Regression: drainAndCache previously left delegateClosed=false on the failure
        // path, so wrapper.close() would call delegate.close() after .use{} had already
        // closed the source (and, by ownership, the delegate). Some sockets / streams
        // throw on double-close — the bug only surfaced when both a drain failure and a
        // subsequent close() happened on the same response.
        val seed = Io.provider.buffer().also { it.writeUtf8("ignored") }
        val sourceCloseCount = AtomicInteger(0)
        val throwingSource =
            object : BufferedSource by seed.peek() {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = throw IOException("drain failed")

                override fun close() {
                    sourceCloseCount.incrementAndGet()
                }
            }

        val delegateCloseCount = AtomicInteger(0)
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = MediaType.parse("text/plain")

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = throwingSource

                override fun close() {
                    delegateCloseCount.incrementAndGet()
                }
            }

        val wrapper = LoggableResponseBody(delegate)
        assertFailsWith<IOException> { wrapper.source() }

        wrapper.close()

        assertEquals(
            1,
            sourceCloseCount.get(),
            "the source's .use{} should close the source exactly once on the failure path",
        )
        assertEquals(
            0,
            delegateCloseCount.get(),
            "wrapper.close() must not call delegate.close() after a drain failure — " +
                "the source's close (via .use{}) already owns the delegate-close responsibility",
        )
    }

    @Test
    fun `close after successful drain does not call delegate close`() {
        val delegateCloseCount = AtomicInteger(0)
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = MediaType.parse("text/plain")

                override fun contentLength(): Long = 5

                override fun source(): BufferedSource = Io.provider.buffer().also { it.writeUtf8("hello") }

                override fun close() {
                    delegateCloseCount.incrementAndGet()
                }
            }

        val wrapper = LoggableResponseBody(delegate)
        assertEquals("hello", wrapper.source().readUtf8())
        wrapper.close()

        assertEquals(
            0,
            delegateCloseCount.get(),
            "wrapper.close() must not call delegate.close() after a successful drain",
        )
    }

    // ----- additional failure-semantics tests -----

    @Test
    fun `source() after drainError is set re-throws the cached exception`() {
        val seed = Io.provider.buffer().also { it.writeUtf8("x") }
        val throwingSource =
            object : BufferedSource by seed.peek() {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = throw IOException("network blip")

                override fun close() {}
            }
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = throwingSource

                override fun close() {}
            }
        val wrapper = LoggableResponseBody(delegate)

        // First call triggers the drain, which fails.
        val ex1 = assertFailsWith<IOException> { wrapper.source() }
        assertEquals("network blip", ex1.message ?: ex1.cause?.message)

        // Subsequent calls re-throw the cached exception (or a wrapping IOException).
        val ex2 = assertFailsWith<IOException> { wrapper.source() }
        assertNotNull(ex2)
    }

    @Test
    fun `captureException returns cached drainError after failed drain`() {
        val seed = Io.provider.buffer().also { it.writeUtf8("x") }
        val cause = IOException("forced failure")
        val throwingSource =
            object : BufferedSource by seed.peek() {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = throw cause

                override fun close() {}
            }
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = throwingSource

                override fun close() {}
            }
        val wrapper = LoggableResponseBody(delegate)

        // Before drain: captureException is null.
        assertNull(wrapper.captureException)

        // Trigger drain.
        assertFailsWith<IOException> { wrapper.source() }

        // After failed drain: captureException holds the cause.
        assertNotNull(wrapper.captureException)
    }

    @Test
    fun `captureException returns null after a successful drain`() {
        val wrapper = LoggableResponseBody(bodyFromText("ok"))
        // Trigger drain.
        wrapper.source()
        assertNull(wrapper.captureException)
    }

    @Test
    fun `snapshot(maxBytes) returns a bounded prefix and does not exceed the cap`() {
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes
        val wrapper = LoggableResponseBody(bodyFromText(payload))

        val prefix = wrapper.snapshot(10)
        assertEquals(10, prefix.size)
        assertContentEquals("abcdefghij".toByteArray(Charsets.UTF_8), prefix)

        // A cap larger than the body returns the full body.
        val full = wrapper.snapshot(1000)
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, full.size)
    }

    @Test
    fun `close() before read throws IllegalStateException from source()`() {
        val wrapper = LoggableResponseBody(bodyFromText("data"))
        wrapper.close()

        val ex = assertFailsWith<IllegalStateException> { wrapper.source() }
        assertNotNull(ex.message)
        assertTrue(
            ex.message!!.contains("closed", ignoreCase = true),
            "Expected 'closed' in message, got: ${ex.message}",
        )
    }

    // ----- bounded capture + live-tail behavior -----

    @Test
    fun `over-cap body returns the full bytes to the consumer while snapshot is bounded`() {
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes
        val wrapper = LoggableResponseBody.bounded(bodyFromText(payload), Io.provider, 10L)

        // The in-memory capture is bounded to 10 bytes.
        assertEquals(10, wrapper.snapshot().size, "capture must be bounded to maxCaptureBytes")
        assertContentEquals("abcdefghij".toByteArray(Charsets.UTF_8), wrapper.snapshot())

        // But the consumer still receives the FULL body via source() (prefix + live tail).
        assertEquals(payload, wrapper.source().readUtf8(), "consumer must receive the full body")
        wrapper.close()
    }

    @Test
    fun `second source() on an over-cap body throws IllegalStateException`() {
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes > cap
        val wrapper = LoggableResponseBody.bounded(bodyFromText(payload), Io.provider, 5L)

        // First source() hands out the one-shot prefix+tail stream.
        val first = wrapper.source()
        assertNotNull(first)

        // The live tail is single-use; a second source() must be rejected.
        val ex = assertFailsWith<IllegalStateException> { wrapper.source() }
        assertNotNull(ex.message)
        assertTrue(
            ex.message!!.contains("single-use", ignoreCase = true) ||
                ex.message!!.contains("maxCaptureBytes", ignoreCase = true),
            "Expected an over-cap one-shot message, got: ${ex.message}",
        )
        wrapper.close()
    }

    @Test
    fun `body within the cap stays fully repeatable`() {
        val payload = "small" // 5 bytes <= cap
        val wrapper = LoggableResponseBody.bounded(bodyFromText(payload), Io.provider, 64L)

        // Within the cap: source() yields a fresh repeatable view each time.
        assertEquals(payload, wrapper.source().readUtf8())
        assertEquals(payload, wrapper.source().readUtf8(), "within-cap body must be repeatable")
        // contentLength reflects the fully-captured size.
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), wrapper.contentLength())
        wrapper.close()
    }

    @Test
    fun `over-cap body reports the delegate content length not the captured prefix`() {
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes
        val delegateLength = payload.toByteArray(Charsets.UTF_8).size.toLong()
        val wrapper = LoggableResponseBody.bounded(bodyFromText(payload), Io.provider, 4L)

        // Trigger the bounded drain.
        wrapper.snapshot()
        // Over-cap: contentLength is the delegate's true length, not the 4-byte prefix.
        assertEquals(delegateLength, wrapper.contentLength())
        wrapper.close()
    }

    @Test
    fun `default constructor captures the whole body unbounded and repeatable`() {
        // The public (unlimited) path is unchanged: full capture, repeatable source().
        val payload = "x".repeat(50_000)
        val wrapper = LoggableResponseBody(bodyFromText(payload))
        assertEquals(payload, wrapper.source().readUtf8())
        assertEquals(payload, wrapper.source().readUtf8(), "default path must be fully repeatable")
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, wrapper.snapshot().size)
        wrapper.close()
    }

    @Test
    fun `concurrent source() calls serialize drain and both callers get a view`() {
        val payload = "shared"
        val wrapper = LoggableResponseBody(bodyFromText(payload))

        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(1)
        val results = arrayOfNulls<String>(2)
        val errors = arrayOfNulls<Throwable>(2)

        for (i in 0..1) {
            val idx = i
            executor.submit {
                latch.await()
                try {
                    results[idx] = wrapper.source().readUtf8()
                } catch (t: Throwable) {
                    errors[idx] = t
                }
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

        // Both callers must have successfully read the body without error.
        for (i in 0..1) {
            assertNull(errors[i], "Thread $i got unexpected error: ${errors[i]}")
            assertEquals(payload, results[i], "Thread $i got unexpected data")
        }
    }

    // ----- bounded drain must not spin on a zero-read source -----

    @Test
    fun `drain on a zero-read source fails fast instead of spinning forever`() {
        // A delegate Source that returns 0 for a positive byteCount violates the Source.read
        // contract. The bounded drain subtracts the read count from `remaining`, so a 0-read
        // is a no-op that loops forever. The drain must instead fail fast with an IOException,
        // and source() must re-throw it (rather than hang the caller / worker / completion thread).
        val seed = Io.provider.buffer().also { it.writeUtf8("ignored") }
        val zeroReadSource =
            object : BufferedSource by seed.peek() {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = 0L

                override fun close() {}
            }
        val delegate =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = zeroReadSource

                override fun close() {}
            }
        val wrapper = LoggableResponseBody(delegate)

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            // The drain must terminate; it must not spin on the zero-read.
            val ex = assertFailsWith<IOException> { wrapper.source() }
            assertNotNull(ex)
        }

        // The drain failure is cached as an IOException and surfaced via captureException.
        val captured = wrapper.captureException
        assertNotNull(captured, "a zero-read drain must cache a failure, not truncate silently")
        assertTrue(
            captured is IOException,
            "the cached drain failure must be an IOException, got: ${captured::class}",
        )
        // The partial snapshot is still available (documented failure semantics).
        assertEquals(0, wrapper.snapshot().size, "no bytes were read before the contract violation")
    }

    // ----- over-cap live tail must be closed exactly once -----

    /**
     * A delegate that returns the SAME [BufferedSource] instance on every [source] call and whose
     * [close] actually closes that instance, counting underlying closes. Models a BYO transport
     * whose `source()` returns one source and whose `close()` closes it — the case where routing
     * the live-tail close separately from the delegate close double-closes the socket.
     */
    private class SharedSourceBody(
        private val text: String,
        sourceCloseCount: AtomicInteger,
    ) : ResponseBody() {
        private val backing: BufferedSource = Io.provider.buffer().also { it.writeUtf8(text) }
        private val shared: BufferedSource =
            object : BufferedSource by backing {
                override fun close() {
                    sourceCloseCount.incrementAndGet()
                }
            }

        override fun mediaType(): MediaType? = MediaType.parse("text/plain")

        override fun contentLength(): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

        override fun source(): BufferedSource = shared

        override fun close() {
            shared.close()
        }
    }

    @Test
    fun `over-cap source then close closes the underlying source exactly once`() {
        // Over-cap: the wrapper retains delegate.source() as the live tail and hands it out inside
        // a one-shot stream. Closing that stream and then closing the wrapper must not close the
        // same underlying source twice (some sockets / streams throw on double-close).
        val sourceCloseCount = AtomicInteger(0)
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes > cap
        val wrapper = LoggableResponseBody.bounded(SharedSourceBody(payload, sourceCloseCount), Io.provider, 5L)

        wrapper.source().use { it.readUtf8() }
        wrapper.close()

        assertEquals(
            1,
            sourceCloseCount.get(),
            "the underlying source must be closed exactly once across the one-shot stream and wrapper close",
        )
    }

    @Test
    fun `over-cap close then source close closes the underlying source exactly once`() {
        // The reverse order: wrapper.close() first, then closing the already-handed-out one-shot
        // stream. Whichever path closes the delegate first wins; the second must be a no-op.
        val sourceCloseCount = AtomicInteger(0)
        val payload = "abcdefghijklmnopqrstuvwxyz" // 26 bytes > cap
        val wrapper = LoggableResponseBody.bounded(SharedSourceBody(payload, sourceCloseCount), Io.provider, 5L)

        val tail = wrapper.source()
        wrapper.close()
        tail.close()

        assertEquals(
            1,
            sourceCloseCount.get(),
            "the underlying source must be closed exactly once regardless of close ordering",
        )
    }
}
