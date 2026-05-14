package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Exercises [org.dexpace.sdk.io.internal.OkioBufferedSink] — `closedFlag` propagation,
 * `close()` idempotency, and every public method.
 */
class OkioBufferedSinkTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun sink(out: ByteArrayOutputStream = ByteArrayOutputStream()): Pair<BufferedSink, ByteArrayOutputStream> {
        return OkioIoProvider.sink(out) to out
    }

    @Test
    fun `write Buffer with OkioBuffer source uses fast path`() {
        val (s, out) = sink()
        val src = OkioIoProvider.buffer().apply { writeUtf8("hello") }
        s.write(src, 5)
        s.flush()
        assertContentEquals("hello".toByteArray(), out.toByteArray())
    }

    @Test
    fun `write Buffer with non-Okio source falls back to byte array path`() {
        val (s, out) = sink()
        val src = NonOkioForeignBuffer().apply { writeBytes("hello".toByteArray()) }
        s.write(src, 5)
        s.flush()
        assertContentEquals("hello".toByteArray(), out.toByteArray())
    }

    @Test
    fun `write byte array writes through`() {
        val (s, out) = sink()
        s.write("abc".toByteArray())
        s.flush()
        assertContentEquals("abc".toByteArray(), out.toByteArray())
    }

    @Test
    fun `write byte array with offset and count`() {
        val (s, out) = sink()
        s.write("01234567".toByteArray(), 2, 4)
        s.flush()
        assertContentEquals("2345".toByteArray(), out.toByteArray())
    }

    @Test
    fun `flush forces output`() {
        val (s, out) = sink()
        s.writeUtf8("payload")
        s.flush()
        assertContentEquals("payload".toByteArray(), out.toByteArray())
    }

    @Test
    fun `emit returns this for chaining`() {
        val (s, _) = sink()
        val ret = s.writeUtf8("data").emit()
        assertSame(s, ret)
    }

    @Test
    fun `writeAll drains source into sink`() {
        val (s, out) = sink()
        val src = OkioIoProvider.source("ABCD".toByteArray())
        val n = s.writeAll(src)
        s.flush()
        assertEquals(4L, n)
        assertContentEquals("ABCD".toByteArray(), out.toByteArray())
    }

    @Test
    fun `writeUtf8 writes utf-8 bytes`() {
        val (s, out) = sink()
        s.writeUtf8("héllo")
        s.flush()
        assertContentEquals("héllo".toByteArray(Charsets.UTF_8), out.toByteArray())
    }

    @Test
    fun `writeUtf8 with begin and end indices`() {
        val (s, out) = sink()
        s.writeUtf8("hello, world", 7, 12)
        s.flush()
        assertContentEquals("world".toByteArray(), out.toByteArray())
    }

    @Test
    fun `writeString encodes with charset`() {
        val (s, out) = sink()
        s.writeString("héllo", Charsets.ISO_8859_1)
        s.flush()
        assertContentEquals("héllo".toByteArray(Charsets.ISO_8859_1), out.toByteArray())
    }

    @Test
    fun `outputStream bridges to java io`() {
        val (s, out) = sink()
        val stream = s.outputStream()
        stream.write("bridged".toByteArray())
        stream.flush()
        assertContentEquals("bridged".toByteArray(), out.toByteArray())
    }

    @Test
    fun `buffer property returns the adapter's internal staging buffer`() {
        val (s, _) = sink()
        val internal = s.buffer
        // Just verifying lazy init does not throw and yields a usable Buffer.
        internal.writeUtf8("staged")
        assertEquals(6L, internal.size)
    }

    // ----- closedFlag behavior -----

    @Test
    fun `close is idempotent`() {
        val (s, _) = sink()
        s.close()
        // Second close must not throw and must not double-close the delegate (compareAndSet
        // guards against it).
        s.close()
    }

    @Test
    fun `write Buffer after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        val src = OkioIoProvider.buffer().apply { writeUtf8("data") }
        assertFailsWith<IOException> { s.write(src, 4) }
    }

    @Test
    fun `write byte array after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.write("data".toByteArray()) }
    }

    @Test
    fun `write byte array with offset after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.write("data".toByteArray(), 0, 4) }
    }

    @Test
    fun `flush after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.flush() }
    }

    @Test
    fun `writeAll after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> {
            s.writeAll(OkioIoProvider.source("data".toByteArray()))
        }
    }

    @Test
    fun `writeUtf8 after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.writeUtf8("data") }
    }

    @Test
    fun `writeUtf8 with indices after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.writeUtf8("data", 0, 4) }
    }

    @Test
    fun `writeString after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.writeString("data", Charsets.UTF_8) }
    }

    @Test
    fun `outputStream after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.outputStream() }
    }

    @Test
    fun `emit after close throws IOException`() {
        val (s, _) = sink()
        s.close()
        assertFailsWith<IOException> { s.emit() }
    }

    @Test
    fun `write Buffer rejects negative byteCount before checking closed flag`() {
        val (s, _) = sink()
        val src = OkioIoProvider.buffer().apply { writeUtf8("data") }
        // require(byteCount >= 0) is evaluated before checkOpen.
        assertFailsWith<IllegalArgumentException> { s.write(src, -1) }
    }

    /** Minimal non-OkioBuffer Buffer that returns bytes via readByteArray(byteCount). */
    private class NonOkioForeignBuffer : org.dexpace.sdk.core.io.Buffer {
        private val store = ByteArrayOutputStream()
        private var read = 0
        private var contents: ByteArray? = null

        fun writeBytes(b: ByteArray) {
            store.write(b)
            contents = null
        }

        private fun materialize(): ByteArray {
            val c = contents
            if (c != null) return c
            val arr = store.toByteArray()
            contents = arr
            return arr
        }

        override val size: Long get() = (materialize().size - read).toLong()

        override fun snapshot(): ByteArray {
            val arr = materialize()
            val rem = ByteArray(arr.size - read)
            System.arraycopy(arr, read, rem, 0, rem.size)
            return rem
        }

        override fun clear() {
            store.reset()
            contents = null
            read = 0
        }

        override fun copyTo(
            out: org.dexpace.sdk.core.io.Buffer,
            offset: Long,
            byteCount: Long,
        ): org.dexpace.sdk.core.io.Buffer {
            out.write(materialize(), read + offset.toInt(), byteCount.toInt())
            return this
        }

        override val buffer: org.dexpace.sdk.core.io.Buffer get() = this

        override fun exhausted(): Boolean = size == 0L

        override fun readByte(): Byte = materialize()[read++]

        override fun readByteArray(): ByteArray {
            val r = snapshot()
            read = materialize().size
            return r
        }

        override fun readByteArray(byteCount: Long): ByteArray {
            val arr = materialize()
            val out = ByteArray(byteCount.toInt())
            System.arraycopy(arr, read, out, 0, out.size)
            read += out.size
            return out
        }

        override fun readUtf8(): String = String(readByteArray(), Charsets.UTF_8)

        override fun readUtf8(byteCount: Long): String = String(readByteArray(byteCount), Charsets.UTF_8)

        override fun readUtf8Line(): String? = null

        override fun readString(charset: java.nio.charset.Charset): String = String(readByteArray(), charset)

        override fun peek(): org.dexpace.sdk.core.io.BufferedSource = throw UnsupportedOperationException()

        override fun inputStream(): java.io.InputStream = throw UnsupportedOperationException()

        override fun skip(byteCount: Long) {
            read += byteCount.toInt()
        }

        override fun slice(
            offset: Long,
            byteCount: Long,
        ): org.dexpace.sdk.core.io.BufferedSource = throw UnsupportedOperationException()

        override fun read(
            sink: org.dexpace.sdk.core.io.Buffer,
            byteCount: Long,
        ): Long {
            if (exhausted()) return -1L
            val avail = minOf(byteCount, size)
            sink.write(readByteArray(avail))
            return avail
        }

        override fun write(source: ByteArray): org.dexpace.sdk.core.io.BufferedSink {
            writeBytes(source)
            return this
        }

        override fun write(
            source: ByteArray,
            offset: Int,
            byteCount: Int,
        ): org.dexpace.sdk.core.io.BufferedSink {
            store.write(source, offset, byteCount)
            contents = null
            return this
        }

        override fun writeAll(source: org.dexpace.sdk.core.io.Source): Long = 0L

        override fun writeUtf8(string: String): org.dexpace.sdk.core.io.BufferedSink {
            writeBytes(string.toByteArray())
            return this
        }

        override fun writeUtf8(
            string: String,
            beginIndex: Int,
            endIndex: Int,
        ): org.dexpace.sdk.core.io.BufferedSink {
            writeBytes(string.substring(beginIndex, endIndex).toByteArray())
            return this
        }

        override fun writeString(
            string: String,
            charset: java.nio.charset.Charset,
        ): org.dexpace.sdk.core.io.BufferedSink {
            writeBytes(string.toByteArray(charset))
            return this
        }

        override fun outputStream(): java.io.OutputStream = throw UnsupportedOperationException()

        override fun emit(): org.dexpace.sdk.core.io.BufferedSink = this

        override fun write(
            source: org.dexpace.sdk.core.io.Buffer,
            byteCount: Long,
        ) {
            writeBytes(source.readByteArray(byteCount))
        }

        override fun flush() {}

        override fun close() {}
    }
}
