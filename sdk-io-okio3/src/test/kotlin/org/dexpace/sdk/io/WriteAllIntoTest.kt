package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.Source
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [org.dexpace.sdk.io.internal.writeAllInto] — specifically the fallback path that
 * fires when the source is **not** an `OkioBufferedSource` or `OkioBuffer`.
 *
 * The Okio-fast paths are already covered by `OkioIoProviderTest`. Here we focus on the
 * `else` branch that does a pump loop through an intermediate `OkioBuffer`, and on the
 * `read <= 0` guard against a foreign Source that returns 0.
 */
class WriteAllIntoTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    /** A custom plain [BufferedSource] that does NOT extend Okio's adapters — forces slow path. */
    private class PlainBufferedSource(data: ByteArray) : BufferedSource {
        private val buf = OkioIoProvider.buffer().apply { write(data) }
        override val buffer: Buffer get() = buf

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long = buf.read(sink, byteCount)

        override fun close() {}

        override fun exhausted(): Boolean = buf.exhausted()

        override fun readByte(): Byte = buf.readByte()

        override fun readByteArray(): ByteArray = buf.readByteArray()

        override fun readByteArray(byteCount: Long): ByteArray = buf.readByteArray(byteCount)

        override fun readUtf8(): String = buf.readUtf8()

        override fun readUtf8(byteCount: Long): String = buf.readUtf8(byteCount)

        override fun readUtf8Line(): String? = buf.readUtf8Line()

        override fun readString(charset: Charset): String = buf.readString(charset)

        override fun peek(): BufferedSource = throw UnsupportedOperationException()

        override fun inputStream(): java.io.InputStream = throw UnsupportedOperationException()

        override fun skip(byteCount: Long) {
            buf.skip(byteCount)
        }

        override fun slice(
            offset: Long,
            byteCount: Long,
        ): BufferedSource = throw UnsupportedOperationException()
    }

    /** Foreign Source that returns 0 on first read — ensures the slow path's guard short-circuits. */
    private class ZeroSource : Source {
        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long = 0L

        override fun close() {}
    }

    @Test
    fun `writeAll over a plain BufferedSource takes the slow path and copies bytes`() {
        val out = ByteArrayOutputStream()
        val sink: BufferedSink = OkioIoProvider.sink(out)
        val source = PlainBufferedSource("payload".toByteArray())
        val n = sink.writeAll(source)
        sink.flush()
        assertEquals(7L, n)
        assertContentEquals("payload".toByteArray(), out.toByteArray())
    }

    @Test
    fun `writeAll terminates on a Source that returns zero`() {
        val out = ByteArrayOutputStream()
        val sink: BufferedSink = OkioIoProvider.sink(out)
        val n = sink.writeAll(ZeroSource())
        sink.flush()
        assertEquals(0L, n)
        assertContentEquals(ByteArray(0), out.toByteArray())
    }

    @Test
    fun `writeAll over a large plain source pumps multiple segments`() {
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val sink: BufferedSink = OkioIoProvider.sink(out)
        val source = PlainBufferedSource(payload)
        val n = sink.writeAll(source)
        sink.flush()
        assertEquals(payload.size.toLong(), n)
        assertContentEquals(payload, out.toByteArray())
    }

    @Test
    fun `OkioBuffer writeAll falls back to slow path for plain BufferedSource`() {
        // OkioBuffer.writeAll also routes through writeAllInto; verify the slow path lands
        // bytes into the buffer correctly.
        val dst = OkioIoProvider.buffer()
        val source = PlainBufferedSource("buffered".toByteArray())
        val n = dst.writeAll(source)
        assertEquals(8L, n)
        assertEquals("buffered", dst.readUtf8())
    }

    @Test
    fun `OkioBuffer writeAll uses fast path for an OkioBuffer source`() {
        val dst = OkioIoProvider.buffer()
        val src = OkioIoProvider.buffer().apply { writeUtf8("fast-path") }
        val n = dst.writeAll(src)
        // OkioBuffer-to-OkioBuffer goes through the fast path; bytes still land identically.
        assertEquals(9L, n)
        assertEquals("fast-path", dst.readUtf8())
        assertTrue(src.exhausted())
    }
}
