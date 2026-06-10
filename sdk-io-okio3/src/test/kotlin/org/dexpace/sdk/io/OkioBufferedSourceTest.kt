/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [org.dexpace.sdk.io.internal.OkioBufferedSource] — specifically the
 * non-OkioBuffer branch of `read(sink, byteCount)`, `slice()`, and every typed-read
 * forwarder.
 *
 * `OkioIoProvider.source(InputStream)` returns an `OkioBufferedSource` (whereas
 * `source(ByteArray)` returns an `OkioBuffer`). All tests here use the InputStream factory
 * to exercise the OkioBufferedSource class explicitly.
 */
class OkioBufferedSourceTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun streamSource(bytes: ByteArray): BufferedSource = OkioIoProvider.source(ByteArrayInputStream(bytes))

    private fun streamSource(string: String): BufferedSource = streamSource(string.toByteArray())

    // ----- read(sink, byteCount) branches -----

    @Test
    fun `read into OkioBuffer fast path moves bytes`() {
        val src = streamSource("payload")
        val dst = OkioIoProvider.buffer()
        val n = src.read(dst, 7)
        assertEquals(7L, n)
        assertEquals("payload", dst.readUtf8())
    }

    @Test
    fun `read into non-Okio Buffer falls back to slow path`() {
        val src = streamSource("hello")
        val dst = NonOkioBuffer()
        val n = src.read(dst, 5)
        assertTrue(n in 1..5, "expected 1..5 bytes (got $n)")
        // Drain anything remaining for safety.
        while (!src.exhausted()) {
            src.read(dst, 16)
        }
        assertContentEquals("hello".toByteArray(), dst.snapshot())
    }

    @Test
    fun `read into non-Okio Buffer with zero byteCount returns 0`() {
        val src = streamSource("xyz")
        val dst = NonOkioBuffer()
        assertEquals(0L, src.read(dst, 0))
        // Source untouched.
        assertFalse(src.exhausted())
    }

    @Test
    fun `read into OkioBuffer with zero byteCount returns 0 on a non-empty source`() {
        val src = streamSource("xyz")
        val dst = OkioIoProvider.buffer()
        // A zero-byte read must report 0 (no progress), never EOF — even down the OkioBuffer
        // fast path. The zero check is hoisted above the fast path so both branches agree.
        assertEquals(0L, src.read(dst, 0))
        assertEquals(0L, dst.size)
        assertFalse(src.exhausted())
    }

    @Test
    fun `read into OkioBuffer with zero byteCount returns 0 on an exhausted source`() {
        // Regression: the OkioBuffer fast path used to run before the zero-byteCount check, so
        // a zero-byte read against an exhausted Okio-backed source returned Okio's -1 (EOF)
        // instead of the contractual 0. read(sink, 0) must NEVER report EOF.
        val src = streamSource(ByteArray(0))
        assertTrue(src.exhausted())
        val dst = OkioIoProvider.buffer()
        assertEquals(0L, src.read(dst, 0))
        assertEquals(0L, dst.size)
    }

    @Test
    fun `read into non-Okio Buffer on exhausted source returns -1`() {
        val src = streamSource(ByteArray(0))
        val dst = NonOkioBuffer()
        assertEquals(-1L, src.read(dst, 8))
    }

    @Test
    fun `read after partial drain into non-Okio Buffer continues until exhausted`() {
        // Multi-step pull through the require(1) path: drain into a non-Okio Buffer in chunks.
        val data = "abcdefghij"
        val src = streamSource(data)
        val dst = NonOkioBuffer()
        var total = 0L
        while (!src.exhausted()) {
            val n = src.read(dst, 4)
            if (n <= 0L) break
            total += n
        }
        assertEquals(data.length.toLong(), total)
        assertContentEquals(data.toByteArray(), dst.snapshot())
    }

    @Test
    fun `read rejects negative byteCount`() {
        val src = streamSource("data")
        assertFailsWith<IllegalArgumentException> { src.read(OkioIoProvider.buffer(), -1) }
    }

    // ----- typed forwarders -----

    @Test
    fun `exhausted reflects underlying state`() {
        val src = streamSource("a")
        assertFalse(src.exhausted())
        src.readByte()
        assertTrue(src.exhausted())
    }

    @Test
    fun `readByte returns next byte`() {
        val src = streamSource(byteArrayOf(7, 8, 9))
        assertEquals(7.toByte(), src.readByte())
        assertEquals(8.toByte(), src.readByte())
    }

    @Test
    fun `readByteArray drains remaining`() {
        val src = streamSource("abc")
        assertContentEquals("abc".toByteArray(), src.readByteArray())
        assertTrue(src.exhausted())
    }

    @Test
    fun `readByteArray with byteCount reads exactly`() {
        val src = streamSource("abcde")
        assertContentEquals("abc".toByteArray(), src.readByteArray(3))
        assertEquals("de", src.readUtf8())
    }

    @Test
    fun `readUtf8 reads remaining as utf-8`() {
        val src = streamSource("héllo")
        assertEquals("héllo", src.readUtf8())
    }

    @Test
    fun `readUtf8 with byteCount reads exactly that many bytes`() {
        val src = streamSource("hello-world")
        assertEquals("hello", src.readUtf8(5))
    }

    @Test
    fun `readUtf8Line reads lines`() {
        val src = streamSource("line1\nline2\nlast")
        assertEquals("line1", src.readUtf8Line())
        assertEquals("line2", src.readUtf8Line())
        assertEquals("last", src.readUtf8Line())
        assertEquals(null, src.readUtf8Line())
    }

    @Test
    fun `readString decodes with charset`() {
        val src = streamSource("héllo".toByteArray(Charsets.ISO_8859_1))
        assertEquals("héllo", src.readString(Charsets.ISO_8859_1))
    }

    @Test
    fun `peek returns a non-consuming view`() {
        val src = streamSource("payload")
        val peek = src.peek()
        assertEquals("payload", peek.readUtf8())
        assertEquals("payload", src.readUtf8())
    }

    @Test
    fun `inputStream bridges to java io`() {
        val src = streamSource("io-bridge")
        val stream = src.inputStream()
        val bytes = ByteArray("io-bridge".length)
        val n = stream.read(bytes)
        assertEquals("io-bridge".length, n)
        assertContentEquals("io-bridge".toByteArray(), bytes)
    }

    @Test
    fun `skip discards bytes`() {
        val src = streamSource("01234")
        src.skip(2)
        assertEquals("234", src.readUtf8())
    }

    // ----- slice on OkioBufferedSource -----

    @Test
    fun `slice on OkioBufferedSource returns a length-bounded view`() {
        val src = streamSource("hello, world")
        val slice = src.slice(7, 5)
        assertEquals("world", slice.readUtf8())
    }

    @Test
    fun `slice on OkioBufferedSource rejects negative arguments`() {
        val src = streamSource("data")
        assertFailsWith<IllegalArgumentException> { src.slice(-1, 0) }
        assertFailsWith<IllegalArgumentException> { src.slice(0, -1) }
    }

    @Test
    fun `closing the OkioBufferedSource invalidates its slices`() {
        val src = streamSource("payload")
        val slice = src.slice(0, 7)
        src.close()
        assertFailsWith<IllegalStateException> { slice.readByte() }
    }

    // ----- close-flag isolation (H6) -----

    @Test
    fun `closing a peek view does not prevent root from closing its delegate`() {
        val closeCount = java.util.concurrent.atomic.AtomicInteger(0)
        // Use a counting InputStream so we can observe delegate close() calls.
        val bytes = "hello".toByteArray()
        val trackingStream =
            object : java.io.InputStream() {
                private val inner = java.io.ByteArrayInputStream(bytes)

                override fun read(): Int = inner.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = inner.read(b, off, len)

                override fun close() {
                    closeCount.incrementAndGet()
                    super.close()
                }
            }
        val root = OkioIoProvider.source(trackingStream)
        val peek = root.peek()

        // Close the peek view first — this must NOT close the root's underlying transport.
        peek.close()
        assertEquals(0, closeCount.get(), "closing the peek should not close the root's delegate")

        // Now close the root — the underlying InputStream must be closed exactly once.
        root.close()
        assertEquals(1, closeCount.get(), "root.close() must close the delegate exactly once")
    }

    @Test
    fun `closing root source invalidates its peek views`() {
        val src = streamSource("payload")
        val peek = src.peek()
        src.close()
        assertFailsWith<java.io.IOException> { peek.readByte() }
    }

    // ----- A minimal non-Okio Buffer used to force the fallback branch -----

    private class NonOkioBuffer : Buffer {
        private val store = java.io.ByteArrayOutputStream()
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
            out.write(src, read + offset.toInt(), byteCount.toInt())
            return this
        }

        override val buffer: Buffer get() = this

        override fun exhausted(): Boolean = size == 0L

        override fun readByte(): Byte = materialize()[read++]

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

        override fun peek(): BufferedSource = throw UnsupportedOperationException("unused")

        override fun inputStream(): InputStream = throw UnsupportedOperationException("unused")

        override fun skip(byteCount: Long) {
            read += byteCount.toInt()
        }

        override fun slice(
            offset: Long,
            byteCount: Long,
        ): BufferedSource = throw UnsupportedOperationException("unused")

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (exhausted()) return -1L
            if (byteCount == 0L) return 0L
            val avail = minOf(byteCount, size)
            sink.write(readByteArray(avail))
            return avail
        }

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

        override fun writeAll(source: org.dexpace.sdk.core.io.Source): Long = 0L

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
            write(source.readByteArray(byteCount))
        }

        override fun flush() {}

        override fun close() {}
    }
}
