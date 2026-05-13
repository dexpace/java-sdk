package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoggableResponseBodyTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `close after drain failure does not double-close the delegate`() {
        // Regression: drainAndCache previously left delegateClosed=false on the failure
        // path, so wrapper.close() would call delegate.close() after .use{} had already
        // closed the source (and, by ownership, the delegate). Some sockets / streams
        // throw on double-close — the bug only surfaced when both a drain failure and a
        // subsequent close() happened on the same response. delegateClosed is now set in
        // a finally block, so close() skips the redundant call regardless of drain outcome.
        val seed = Io.provider.buffer().also { it.writeUtf8("ignored") }
        val sourceCloseCount = AtomicInteger(0)
        val throwingSource = object : BufferedSource by seed.peek() {
            override fun read(sink: Buffer, byteCount: Long): Long =
                throw IOException("drain failed")

            override fun close() {
                sourceCloseCount.incrementAndGet()
            }
        }

        val delegateCloseCount = AtomicInteger(0)
        val delegate = object : ResponseBody() {
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
            1, sourceCloseCount.get(),
            "the source's .use{} should close the source exactly once on the failure path",
        )
        assertEquals(
            0, delegateCloseCount.get(),
            "wrapper.close() must not call delegate.close() after a drain failure — " +
                "the source's close (via .use{}) already owns the delegate-close responsibility",
        )
    }

    @Test
    fun `close after successful drain does not call delegate close`() {
        // Complementary case: the success path already worked, but lock it in.
        val delegateCloseCount = AtomicInteger(0)
        val delegate = object : ResponseBody() {
            override fun mediaType(): MediaType? = MediaType.parse("text/plain")
            override fun contentLength(): Long = 5
            override fun source(): BufferedSource =
                Io.provider.buffer().also { it.writeUtf8("hello") }

            override fun close() {
                delegateCloseCount.incrementAndGet()
            }
        }

        val wrapper = LoggableResponseBody(delegate)
        assertEquals("hello", wrapper.source().readUtf8())
        wrapper.close()

        assertEquals(
            0, delegateCloseCount.get(),
            "wrapper.close() must not call delegate.close() after a successful drain",
        )
    }
}
