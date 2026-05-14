package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
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

    // ----- H2: source() that throws before entering .use {} -----

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

    // ----- A12: additional failure-semantics tests -----

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
}
