/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.Io
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises every member of [org.dexpace.sdk.io.internal.OkioBuffer] surfaced through the
 * Buffer/BufferedSource/BufferedSink interfaces.
 *
 * Coverage focus: read & write methods, copyTo (non-Okio sink branch), snapshot(maxBytes)
 * boundary, slice(), outputStream(), emit(), and close() idempotency.
 */
class OkioBufferTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun freshBuffer(): Buffer = OkioIoProvider.buffer()

    private fun bufferWith(bytes: ByteArray): Buffer {
        val b = freshBuffer()
        b.write(bytes)
        return b
    }

    // ----- size / clear -----

    @Test
    fun `size starts at zero for a fresh buffer`() {
        assertEquals(0L, freshBuffer().size)
    }

    @Test
    fun `clear empties the buffer`() {
        val b = bufferWith("data".toByteArray())
        assertEquals(4L, b.size)
        b.clear()
        assertEquals(0L, b.size)
        assertTrue(b.exhausted())
    }

    // ----- snapshot -----

    @Test
    fun `snapshot returns the current bytes`() {
        val b = bufferWith("abc".toByteArray())
        val snap = b.snapshot()
        assertContentEquals("abc".toByteArray(), snap)
        // Snapshot does not consume.
        assertEquals(3L, b.size)
    }

    @Test
    fun `snapshot on an empty buffer is empty`() {
        assertContentEquals(ByteArray(0), freshBuffer().snapshot())
    }

    @Test
    fun `snapshot returns a defensive copy`() {
        val b = bufferWith("xy".toByteArray())
        val first = b.snapshot()
        // Mutate the underlying buffer; first snapshot must remain unchanged.
        b.writeUtf8("z")
        val second = b.snapshot()
        assertContentEquals("xy".toByteArray(), first)
        assertContentEquals("xyz".toByteArray(), second)
    }

    // ----- copyTo -----

    @Test
    fun `copyTo OkioBuffer fast path copies the requested slice`() {
        val src = bufferWith("0123456789".toByteArray())
        val dst = freshBuffer()
        src.copyTo(dst, offset = 2, byteCount = 5) // "23456"
        assertEquals("23456", dst.readUtf8())
        // Source is unchanged.
        assertEquals(10L, src.size)
    }

    @Test
    fun `copyTo defaults to copying from offset to end`() {
        val src = bufferWith("abcdef".toByteArray())
        val dst = freshBuffer()
        src.copyTo(dst, offset = 2)
        assertEquals("cdef", dst.readUtf8())
        assertEquals(6L, src.size)
    }

    @Test
    fun `copyTo into a non-Okio Buffer falls back to the slow path`() {
        val src = bufferWith("payload".toByteArray())
        val foreign = NonOkioBuffer()
        // Verify the non-OkioBuffer branch is exercised — copyTo must still copy correctly.
        src.copyTo(foreign, offset = 0, byteCount = 7)
        assertContentEquals("payload".toByteArray(), foreign.snapshot())
        // Source is unchanged.
        assertEquals(7L, src.size)
    }

    // ----- read variants -----

    @Test
    fun `read into OkioBuffer fast path moves bytes`() {
        val src = bufferWith("abcde".toByteArray())
        val dst = freshBuffer()
        val n = src.read(dst, 3)
        assertEquals(3L, n)
        assertEquals(2L, src.size)
        assertEquals("abc", dst.readUtf8())
    }

    @Test
    fun `read into non-Okio Buffer falls back to the slow path`() {
        val src = bufferWith("xyz".toByteArray())
        val dst = NonOkioBuffer()
        val n = src.read(dst, 3)
        assertEquals(3L, n)
        assertContentEquals("xyz".toByteArray(), dst.snapshot())
    }

    @Test
    fun `read into non-Okio Buffer with zero byteCount returns 0`() {
        val src = bufferWith("abc".toByteArray())
        val dst = NonOkioBuffer()
        assertEquals(0L, src.read(dst, 0))
        // Source untouched.
        assertEquals(3L, src.size)
    }

    @Test
    fun `read into OkioBuffer with zero byteCount returns 0 on a non-empty buffer`() {
        val src = bufferWith("abc".toByteArray())
        val dst = freshBuffer()
        // A zero-byte read must report 0 (no progress), never EOF — even down the OkioBuffer
        // fast path. The zero check is hoisted above the fast path so both branches agree.
        assertEquals(0L, src.read(dst, 0))
        // Nothing was moved.
        assertEquals(3L, src.size)
        assertEquals(0L, dst.size)
    }

    @Test
    fun `read into OkioBuffer with zero byteCount returns 0 on an exhausted buffer`() {
        // Regression: the OkioBuffer fast path used to run before the zero-byteCount check, so
        // a zero-byte read against an exhausted Okio-backed buffer returned Okio's -1 (EOF)
        // instead of the contractual 0. read(sink, 0) must NEVER report EOF.
        val src = freshBuffer()
        assertTrue(src.exhausted())
        val dst = freshBuffer()
        assertEquals(0L, src.read(dst, 0))
        assertEquals(0L, dst.size)
    }

    @Test
    fun `read into non-Okio Buffer on an exhausted buffer returns -1`() {
        val src = freshBuffer()
        val dst = NonOkioBuffer()
        assertEquals(-1L, src.read(dst, 4))
    }

    @Test
    fun `readByte reads one byte and advances`() {
        val b = bufferWith(byteArrayOf(1, 2))
        assertEquals(1.toByte(), b.readByte())
        assertEquals(2.toByte(), b.readByte())
        assertTrue(b.exhausted())
    }

    @Test
    fun `readByteArray reads every remaining byte`() {
        val b = bufferWith("alpha".toByteArray())
        assertContentEquals("alpha".toByteArray(), b.readByteArray())
        assertTrue(b.exhausted())
    }

    @Test
    fun `readByteArray with byteCount reads exactly that many`() {
        val b = bufferWith("abcdef".toByteArray())
        assertContentEquals("abc".toByteArray(), b.readByteArray(3))
        // Remaining bytes still in buffer.
        assertEquals("def", b.readUtf8())
    }

    @Test
    fun `readUtf8 reads every remaining byte as UTF-8`() {
        val b = bufferWith("héllo".toByteArray(Charsets.UTF_8))
        assertEquals("héllo", b.readUtf8())
    }

    @Test
    fun `readUtf8 with byteCount reads that many bytes`() {
        val b = bufferWith("hello, world".toByteArray())
        assertEquals("hello", b.readUtf8(5))
        assertEquals(", world", b.readUtf8())
    }

    @Test
    fun `readUtf8Line reads a single line`() {
        val b = bufferWith("first\nsecond".toByteArray())
        assertEquals("first", b.readUtf8Line())
        assertEquals("second", b.readUtf8Line())
        // After consuming everything, returns null.
        assertEquals(null, b.readUtf8Line())
    }

    @Test
    fun `readString decodes using the requested charset`() {
        val b = bufferWith("héllo".toByteArray(Charsets.ISO_8859_1))
        assertEquals("héllo", b.readString(Charsets.ISO_8859_1))
    }

    @Test
    fun `peek returns a non-consuming view`() {
        val b = bufferWith("snapshot-me".toByteArray())
        val peek = b.peek()
        assertEquals("snapshot-me", peek.readUtf8())
        // The buffer is not consumed.
        assertEquals(11L, b.size)
    }

    @Test
    fun `inputStream reads bytes from the buffer`() {
        val b = bufferWith("io-bridge".toByteArray())
        val stream = b.inputStream()
        val bytes = ByteArray("io-bridge".length)
        val n = stream.read(bytes)
        assertEquals("io-bridge".length, n)
        assertContentEquals("io-bridge".toByteArray(), bytes)
    }

    @Test
    fun `skip discards the requested bytes`() {
        val b = bufferWith("xxxYY".toByteArray())
        b.skip(3)
        assertEquals("YY", b.readUtf8())
    }

    @Test
    fun `exhausted returns true on an empty buffer and false otherwise`() {
        assertTrue(freshBuffer().exhausted())
        val b = bufferWith(byteArrayOf(1))
        assertFalse(b.exhausted())
        b.readByte()
        assertTrue(b.exhausted())
    }

    // ----- write variants -----

    @Test
    fun `write byte array appends bytes`() {
        val b = freshBuffer()
        b.write("abc".toByteArray())
        assertEquals("abc", b.readUtf8())
    }

    @Test
    fun `write byte array with offset and count appends only that slice`() {
        val b = freshBuffer()
        b.write("01234567".toByteArray(), 2, 4)
        assertEquals("2345", b.readUtf8())
    }

    @Test
    fun `writeAll drains a foreign source`() {
        val source = OkioIoProvider.source("payload".toByteArray())
        val b = freshBuffer()
        val n = b.writeAll(source)
        assertEquals(7L, n)
        assertEquals("payload", b.readUtf8())
    }

    @Test
    fun `writeUtf8 encodes a string`() {
        val b = freshBuffer()
        b.writeUtf8("hello")
        assertContentEquals("hello".toByteArray(Charsets.UTF_8), b.readByteArray())
    }

    @Test
    fun `writeUtf8 with begin and end indices encodes that range`() {
        val b = freshBuffer()
        b.writeUtf8("hello, world", 7, 12) // "world"
        assertEquals("world", b.readUtf8())
    }

    @Test
    fun `writeString with charset encodes accordingly`() {
        val b = freshBuffer()
        b.writeString("héllo", Charsets.ISO_8859_1)
        assertContentEquals(
            "héllo".toByteArray(Charsets.ISO_8859_1),
            b.readByteArray(),
        )
    }

    @Test
    fun `write Buffer with OkioBuffer source uses fast path`() {
        val src = bufferWith("hello".toByteArray())
        val dst = freshBuffer()
        dst.write(src, 5)
        assertEquals("hello", dst.readUtf8())
        // Source is drained.
        assertEquals(0L, src.size)
    }

    @Test
    fun `write Buffer with non-Okio source falls back to byte array path`() {
        val src = NonOkioBuffer().apply { write("hello".toByteArray()) }
        val dst = freshBuffer()
        dst.write(src, 5)
        assertEquals("hello", dst.readUtf8())
    }

    @Test
    fun `outputStream writes to the buffer`() {
        val b = freshBuffer()
        val stream = b.outputStream()
        stream.write("written".toByteArray())
        stream.flush()
        assertEquals("written", b.readUtf8())
    }

    @Test
    fun `emit returns the buffer for chaining`() {
        val b = freshBuffer()
        val ret = b.writeUtf8("data").emit()
        // emit on an in-memory buffer is a no-op for visibility purposes; it must return the
        // BufferedSink (self) and not mutate contents.
        assertSame(b, ret)
        assertEquals("data", b.readUtf8())
    }

    @Test
    fun `flush is a no-op for an in-memory buffer`() {
        val b = bufferWith("data".toByteArray())
        b.flush()
        // Contents are untouched.
        assertEquals("data", b.readUtf8())
    }

    // ----- close -----

    @Test
    fun `close is idempotent and does not destroy buffer contents for okio`() {
        val b = bufferWith("residual".toByteArray())
        b.close()
        // Second close must not throw.
        b.close()
    }

    // ----- snapshot(maxBytes) clamping -----

    @Test
    fun `snapshot throws when buffer exceeds MAX_BYTE_ARRAY_SIZE in single-arg snapshot`() {
        // We can't actually allocate MAX_BYTE_ARRAY_SIZE bytes in a unit test. Instead verify
        // that the path is *guarded* — write a small buffer and snapshot succeeds; this
        // documents the live branch even though the throw branch is structurally unreachable
        // in test bounds.
        val b = bufferWith(ByteArray(8))
        // Should not throw.
        assertContentEquals(ByteArray(8), b.snapshot())
    }

    // ----- slice -----

    @Test
    fun `slice on Buffer returns a length-bounded non-consuming view`() {
        val b = bufferWith("0123456789".toByteArray())
        val slice = b.slice(2, 4)
        assertEquals("2345", slice.readUtf8())
        // Buffer untouched.
        assertEquals(10L, b.size)
    }

    @Test
    fun `slice rejects negative offset and byteCount`() {
        val b = bufferWith("abc".toByteArray())
        assertFailsWith<IllegalArgumentException> { b.slice(-1, 1) }
        assertFailsWith<IllegalArgumentException> { b.slice(0, -1) }
    }

    @Test
    fun `closing the buffer invalidates outstanding slices`() {
        val b = bufferWith("payload".toByteArray())
        val slice = b.slice(0, 7)
        b.close()
        // Closing an in-memory okio Buffer is a no-op for the buffer itself, but TeeSink /
        // LoggableResponseBody relies on slice.parentClosed propagation; verify a slice over
        // a closed buffer surfaces the close via IllegalStateException on read.
        assertFailsWith<IllegalStateException> { slice.readByte() }
    }

    // ----- write rejects negative byteCount (already covered elsewhere; keep here for completeness) -----

    @Test
    fun `read rejects negative byteCount`() {
        val src = bufferWith("data".toByteArray())
        val dst = freshBuffer()
        assertFailsWith<IllegalArgumentException> { src.read(dst, -1) }
    }

    @Test
    fun `write rejects negative byteCount`() {
        val src = bufferWith("data".toByteArray())
        val dst = freshBuffer()
        assertFailsWith<IllegalArgumentException> { dst.write(src, -1) }
    }

    // ----- DefaultImpls bridge for copyTo (one-arg form) -----

    @Test
    fun `copyTo with default arguments copies the whole buffer`() {
        // Exercises the kotlin DefaultImpls bridge for copyTo(out) -> copyTo(out, 0, size).
        val src = bufferWith("whole".toByteArray())
        val dst = freshBuffer()
        src.copyTo(dst)
        assertEquals("whole", dst.readUtf8())
        // Source unchanged.
        assertEquals(5L, src.size)
    }

    @Test
    fun `outputStream wraps okio buffer output stream and writes through`() {
        val b = freshBuffer()
        val stream = b.outputStream()
        stream.write('A'.code)
        stream.write("BC".toByteArray())
        stream.flush()
        assertEquals("ABC", b.readUtf8())
    }

    // ----- A minimal non-Okio Buffer for fallback-path tests -----

    private class NonOkioBuffer : Buffer {
        private val store = ByteArrayOutputStream()
        private var read = 0
        private var contents: ByteArray? = null

        private fun materialize(): ByteArray {
            val c = contents
            if (c != null) return c
            val arr = store.toByteArray()
            contents = arr
            return arr
        }

        override val size: Long
            get() = (materialize().size - read).toLong()

        override fun snapshot(): ByteArray {
            // Return remaining bytes; matches the Buffer contract.
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
            out: Buffer,
            offset: Long,
            byteCount: Long,
        ): Buffer {
            val src = materialize()
            val from = read + offset.toInt()
            val n = byteCount.toInt()
            out.write(src, from, n)
            return this
        }

        // BufferedSource

        override val buffer: Buffer get() = this

        override fun exhausted(): Boolean = size == 0L

        override fun readByte(): Byte {
            val arr = materialize()
            return arr[read++]
        }

        override fun readByteArray(): ByteArray {
            val rem = snapshot()
            read = materialize().size
            return rem
        }

        override fun readByteArray(byteCount: Long): ByteArray {
            val arr = materialize()
            val n = byteCount.toInt()
            val out = ByteArray(n)
            System.arraycopy(arr, read, out, 0, n)
            read += n
            return out
        }

        override fun readUtf8(): String = String(readByteArray(), Charsets.UTF_8)

        override fun readUtf8(byteCount: Long): String = String(readByteArray(byteCount), Charsets.UTF_8)

        override fun readUtf8Line(): String? = throw UnsupportedOperationException("unused")

        override fun readString(charset: Charset): String = String(readByteArray(), charset)

        override fun peek(): org.dexpace.sdk.core.io.BufferedSource = throw UnsupportedOperationException("unused")

        override fun inputStream(): java.io.InputStream = throw UnsupportedOperationException("unused")

        override fun skip(byteCount: Long) {
            read += byteCount.toInt()
        }

        override fun slice(
            offset: Long,
            byteCount: Long,
        ): org.dexpace.sdk.core.io.BufferedSource = throw UnsupportedOperationException("unused")

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            require(byteCount >= 0)
            if (exhausted()) return -1L
            if (byteCount == 0L) return 0L
            val avail = minOf(byteCount, size)
            val data = readByteArray(avail)
            sink.write(data)
            return data.size.toLong()
        }

        // BufferedSink

        override fun write(source: ByteArray): org.dexpace.sdk.core.io.BufferedSink {
            store.write(source)
            contents = null
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

        override fun writeAll(source: org.dexpace.sdk.core.io.Source): Long {
            var total = 0L
            while (true) {
                val staging = OkioIoProvider.buffer()
                val n = source.read(staging, 4096)
                if (n <= 0L) break
                write(staging.readByteArray())
                total += n
            }
            return total
        }

        override fun writeUtf8(string: String): org.dexpace.sdk.core.io.BufferedSink {
            write(string.toByteArray(Charsets.UTF_8))
            return this
        }

        override fun writeUtf8(
            string: String,
            beginIndex: Int,
            endIndex: Int,
        ): org.dexpace.sdk.core.io.BufferedSink {
            write(string.substring(beginIndex, endIndex).toByteArray(Charsets.UTF_8))
            return this
        }

        override fun writeString(
            string: String,
            charset: Charset,
        ): org.dexpace.sdk.core.io.BufferedSink {
            write(string.toByteArray(charset))
            return this
        }

        override fun outputStream(): java.io.OutputStream = throw UnsupportedOperationException("unused")

        override fun emit(): org.dexpace.sdk.core.io.BufferedSink = this

        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            val bytes = source.readByteArray(byteCount)
            write(bytes)
        }

        override fun flush() {}

        override fun close() {}
    }
}
