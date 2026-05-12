package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.Source
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [org.dexpace.sdk.io.internal.ForeignSourceAdapter] through
 * `OkioIoProvider.bufferedSource(Source)` — the only public path that exercises it.
 *
 * Coverage focus: identity-cache for the [OkioBuffer] wrapper, close forwarding, repeated
 * reads exercising the cached-wrapper branch, and exhaustion semantics.
 */
class ForeignSourceAdapterTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    /**
     * A foreign [Source] that yields a fixed byte stream and tallies how many times it has
     * been read or closed.
     */
    private class RecordingSource(private val data: ByteArray) : Source {
        var closeCount = 0
        private var offset = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (offset >= data.size) return -1
            val n = minOf(byteCount.toInt(), data.size - offset)
            sink.write(data, offset, n)
            offset += n
            return n.toLong()
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun `reads from a foreign source flow through the adapter`() {
        val src = OkioIoProvider.bufferedSource(RecordingSource("hello".toByteArray()))
        assertEquals("hello", src.readUtf8())
        assertTrue(src.exhausted())
    }

    @Test
    fun `large reads exercise the cached wrapper across multiple Okio read calls`() {
        // 50KB exceeds one segment, forcing Okio to call ForeignSourceAdapter.read multiple
        // times with the SAME okio.Buffer reference. The second-and-subsequent calls go down
        // the cached-wrapper branch (cachedWrapper.takeIf { ... } != null).
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val src: BufferedSource = OkioIoProvider.bufferedSource(RecordingSource(payload))
        assertContentEquals(payload, src.readByteArray())
    }

    @Test
    fun `close forwards to the delegate Source`() {
        val recording = RecordingSource("data".toByteArray())
        val src: BufferedSource = OkioIoProvider.bufferedSource(recording)
        src.close()
        assertEquals(1, recording.closeCount)
    }

    @Test
    fun `exhausted is reported even for empty foreign Source`() {
        val src: BufferedSource = OkioIoProvider.bufferedSource(RecordingSource(ByteArray(0)))
        assertTrue(src.exhausted())
    }

    @Test
    fun `multi-chunk reads consume entire foreign source`() {
        val data = "0123456789ABCDEF".toByteArray()
        val src: BufferedSource = OkioIoProvider.bufferedSource(RecordingSource(data))
        // Pull in 4-byte chunks; each pull exercises the cached-wrapper path on Okio's call
        // into ForeignSourceAdapter.read.
        val out = ByteArray(data.size)
        var read = 0
        while (read < data.size) {
            val staging = OkioIoProvider.buffer()
            val n = src.read(staging, 4).toInt()
            if (n <= 0) break
            val bytes = staging.readByteArray()
            System.arraycopy(bytes, 0, out, read, bytes.size)
            read += bytes.size
        }
        assertContentEquals(data, out)
    }
}
