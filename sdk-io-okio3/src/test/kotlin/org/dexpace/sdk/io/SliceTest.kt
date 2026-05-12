package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SliceTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun source(bytes: ByteArray) = OkioIoProvider.source(bytes)

    private fun source(string: String) = OkioIoProvider.source(string.toByteArray())

    // ----- happy paths -----

    @Test
    fun `slice from offset 0 reads same bytes as original without consuming parent`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 0, byteCount = 12)
        assertEquals("hello, world", slice.readUtf8())
        // Parent cursor not advanced.
        assertEquals("hello, world", parent.readUtf8())
    }

    @Test
    fun `slice from offset 5 byteCount 10 reads bytes 5 through 14`() {
        val parent = source("0123456789ABCDEFG")
        val slice = parent.slice(offset = 5, byteCount = 10)
        assertEquals("56789ABCDE", slice.readUtf8())
        // Parent untouched.
        assertEquals("0123456789ABCDEFG", parent.readUtf8())
    }

    @Test
    fun `slice byteCount exceeds available reads only what is available then EOF`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 0, byteCount = 100)
        assertEquals("abc", slice.readUtf8())
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice offset exceeds source size first read returns empty`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        // Lazy detection — construction succeeds. First read sees EOF and treats it as empty.
        assertTrue(slice.exhausted())
        assertContentEquals(ByteArray(0), slice.readByteArray())
    }

    @Test
    fun `slice offset exceeds source size readByte throws EOFException`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertFailsWith<EOFException> { slice.readByte() }
    }

    @Test
    fun `slice offset exceeds source size readByteArray with byteCount throws EOFException`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertFailsWith<EOFException> { slice.readByteArray(1L) }
    }

    @Test
    fun `slice of empty source is empty`() {
        val parent = source(ByteArray(0))
        val slice = parent.slice(offset = 0, byteCount = 10)
        assertTrue(slice.exhausted())
        assertEquals("", slice.readUtf8())
    }

    @Test
    fun `slice of empty source readByte throws EOFException`() {
        val parent = source(ByteArray(0))
        val slice = parent.slice(offset = 0, byteCount = 10)
        assertFailsWith<EOFException> { slice.readByte() }
    }

    // ----- argument validation -----

    @Test
    fun `negative offset throws IllegalArgumentException`() {
        val parent = source("abc")
        assertFailsWith<IllegalArgumentException> { parent.slice(offset = -1, byteCount = 1) }
    }

    @Test
    fun `negative byteCount throws IllegalArgumentException`() {
        val parent = source("abc")
        assertFailsWith<IllegalArgumentException> { parent.slice(offset = 0, byteCount = -1) }
    }

    // ----- ownership / lifecycle -----

    @Test
    fun `closing slice does not advance parent cursor`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 4)
        slice.readUtf8(2L) // partially consume slice
        slice.close()
        assertEquals("payload", parent.readUtf8())
    }

    @Test
    fun `closing slice does not close parent`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 3)
        slice.close()
        // Parent still readable.
        assertEquals("payload", parent.readUtf8())
    }

    @Test
    fun `closing parent invalidates slice`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 7)
        parent.close()
        // Per spec: subsequent reads on the slice throw IllegalStateException or IOException.
        assertFailsWith<IllegalStateException> { slice.readUtf8() }
    }

    @Test
    fun `closing parent invalidates slice on readByte`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 7)
        parent.close()
        assertFailsWith<IllegalStateException> { slice.readByte() }
    }

    @Test
    fun `closing parent invalidates slice on exhausted`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 7)
        parent.close()
        assertFailsWith<IllegalStateException> { slice.exhausted() }
    }

    @Test
    fun `reads on closed slice throw IllegalStateException`() {
        val parent = source("payload")
        val slice = parent.slice(offset = 0, byteCount = 7)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readUtf8() }
        assertFailsWith<IllegalStateException> { slice.readByte() }
        assertFailsWith<IllegalStateException> { slice.exhausted() }
    }

    // ----- independence -----

    @Test
    fun `two slices of same source are independent`() {
        val parent = source("abcdefghij")
        val left = parent.slice(offset = 0, byteCount = 5)
        val right = parent.slice(offset = 5, byteCount = 5)
        assertEquals("abcde", left.readUtf8())
        assertEquals("fghij", right.readUtf8())
        // Parent untouched.
        assertEquals("abcdefghij", parent.readUtf8())
    }

    @Test
    fun `two slices interleaved reads independently`() {
        val parent = source("0123456789")
        val a = parent.slice(offset = 0, byteCount = 10)
        val b = parent.slice(offset = 0, byteCount = 10)
        assertEquals('0'.code.toByte(), a.readByte())
        assertEquals('0'.code.toByte(), b.readByte())
        assertEquals('1'.code.toByte(), a.readByte())
        assertEquals('1'.code.toByte(), b.readByte())
    }

    // ----- typed read surface -----

    @Test
    fun `slice supports readUtf8 with byteCount`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        assertEquals("world", slice.readUtf8(5L))
    }

    @Test
    fun `slice supports readByteArray with byteCount`() {
        val parent = source(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        val slice = parent.slice(offset = 2, byteCount = 5)
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6), slice.readByteArray(5L))
    }

    @Test
    fun `slice readByteArray with byteCount greater than slice window throws EOFException`() {
        val parent = source("0123456789")
        val slice = parent.slice(offset = 0, byteCount = 3)
        assertFailsWith<EOFException> { slice.readByteArray(5L) }
    }

    @Test
    fun `slice supports readByte`() {
        val parent = source(byteArrayOf(10, 20, 30, 40))
        val slice = parent.slice(offset = 1, byteCount = 2)
        assertEquals(20.toByte(), slice.readByte())
        assertEquals(30.toByte(), slice.readByte())
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice exhausted returns true when remaining is zero`() {
        val parent = source("abcdef")
        val slice = parent.slice(offset = 0, byteCount = 3)
        assertFalse(slice.exhausted())
        slice.readUtf8(3L)
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice exhausted returns true when underlying source is exhausted before byteCount`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 0, byteCount = 100)
        slice.readUtf8()
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice supports skip`() {
        val parent = source("0123456789")
        val slice = parent.slice(offset = 0, byteCount = 10)
        slice.skip(4L)
        assertEquals("456789", slice.readUtf8())
    }

    @Test
    fun `slice skip beyond window throws EOFException`() {
        val parent = source("0123456789")
        val slice = parent.slice(offset = 0, byteCount = 3)
        assertFailsWith<EOFException> { slice.skip(5L) }
    }

    @Test
    fun `slice supports readUtf8Line`() {
        val parent = source("line one\nline two\nline three")
        val slice = parent.slice(offset = 0, byteCount = "line one\nline two\n".length.toLong())
        assertEquals("line one", slice.readUtf8Line())
        assertEquals("line two", slice.readUtf8Line())
        assertNull(slice.readUtf8Line())
    }

    @Test
    fun `slice supports peek without consuming the slice`() {
        val parent = source("abcdefgh")
        val slice = parent.slice(offset = 0, byteCount = 8)
        val peeked = slice.peek()
        assertEquals("abcdefgh", peeked.readUtf8())
        // Slice cursor untouched.
        assertEquals("abcdefgh", slice.readUtf8())
    }

    @Test
    fun `slice of slice composes offsets and bounds correctly`() {
        val parent = source("0123456789ABCDEF")
        val outer = parent.slice(offset = 2, byteCount = 10) // "23456789AB"
        val inner = outer.slice(offset = 3, byteCount = 4)   // "5678"
        assertEquals("5678", inner.readUtf8())
        // Outer untouched.
        assertEquals("23456789AB", outer.readUtf8())
        // Parent untouched.
        assertEquals("0123456789ABCDEF", parent.readUtf8())
    }

    @Test
    fun `slice of slice caps inner byteCount at outer remaining`() {
        val parent = source("0123456789")
        val outer = parent.slice(offset = 0, byteCount = 5) // "01234"
        val inner = outer.slice(offset = 2, byteCount = 100) // requests more than outer has
        assertEquals("234", inner.readUtf8())
        assertTrue(inner.exhausted())
    }

    @Test
    fun `slice inputStream reads bytes within window`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        val stream = slice.inputStream()
        val out = ByteArray(5)
        val read = stream.read(out, 0, 5)
        assertEquals(5, read)
        assertContentEquals("world".toByteArray(), out)
        assertEquals(-1, stream.read())
    }

    @Test
    fun `slice on Buffer also works`() {
        val buffer = OkioIoProvider.buffer()
        buffer.writeUtf8("hello, world")
        val slice = buffer.slice(offset = 7, byteCount = 5)
        assertEquals("world", slice.readUtf8())
        // Buffer not drained.
        assertEquals(12L, buffer.size)
    }

    // ----- read(Buffer, Long) on a slice -----

    @Test
    fun `slice read into OkioBuffer fast path moves bytes within window`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        val dst = OkioIoProvider.buffer()
        val n = slice.read(dst, 5)
        assertEquals(5L, n)
        assertEquals("world", dst.readUtf8())
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice read into non-Okio Buffer falls back to byte array path`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        val dst = NonOkioBufferForSlice()
        val n = slice.read(dst, 5)
        assertEquals(5L, n)
        assertContentEquals("world".toByteArray(), dst.snapshot())
    }

    @Test
    fun `slice read with zero byteCount returns 0`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        assertEquals(0L, slice.read(OkioIoProvider.buffer(), 0))
    }

    @Test
    fun `slice read rejects negative byteCount`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { slice.read(OkioIoProvider.buffer(), -1) }
    }

    @Test
    fun `slice read returns -1 after offset exceeds source size`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertEquals(-1L, slice.read(OkioIoProvider.buffer(), 4))
    }

    @Test
    fun `slice read returns -1 when window is exhausted`() {
        val parent = source("hello")
        val slice = parent.slice(0, 5)
        slice.readUtf8()
        assertEquals(-1L, slice.read(OkioIoProvider.buffer(), 4))
    }

    @Test
    fun `slice read into non-Okio Buffer when offset exceeds source returns -1`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertEquals(-1L, slice.read(NonOkioBufferForSlice(), 4))
    }

    @Test
    fun `slice read after close throws IllegalStateException`() {
        val parent = source("payload")
        val slice = parent.slice(0, 7)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.read(OkioIoProvider.buffer(), 4) }
    }

    // ----- readByteArray() boundary -----

    @Test
    fun `slice readByteArray returns remaining bytes`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        assertContentEquals("world".toByteArray(), slice.readByteArray())
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice readByteArray when offset exceeds source returns empty array`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertContentEquals(ByteArray(0), slice.readByteArray())
    }

    @Test
    fun `slice readByteArray when exhausted returns empty array`() {
        val parent = source("hi")
        val slice = parent.slice(0, 2)
        slice.readUtf8()
        assertContentEquals(ByteArray(0), slice.readByteArray())
    }

    @Test
    fun `slice readByteArray after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readByteArray() }
    }

    // ----- readByteArray(byteCount) boundary -----

    @Test
    fun `slice readByteArray with byteCount zero returns empty array`() {
        val parent = source("hello")
        val slice = parent.slice(0, 5)
        assertContentEquals(ByteArray(0), slice.readByteArray(0))
    }

    @Test
    fun `slice readByteArray with byteCount zero on out-of-range slice returns empty array`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertContentEquals(ByteArray(0), slice.readByteArray(0))
    }

    @Test
    fun `slice readByteArray with negative byteCount throws IllegalArgumentException`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { slice.readByteArray(-1) }
    }

    @Test
    fun `slice readByteArray rejects byteCount above MAX_BYTE_ARRAY_SIZE`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        val tooBig = Buffer.MAX_BYTE_ARRAY_SIZE.toLong() + 1
        assertFailsWith<IllegalArgumentException> { slice.readByteArray(tooBig) }
    }

    @Test
    fun `slice readByteArray with byteCount after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readByteArray(2) }
    }

    // ----- readUtf8 forwarders -----

    @Test
    fun `slice readUtf8 returns remaining bytes as utf-8`() {
        val parent = source("hello, world")
        val slice = parent.slice(offset = 7, byteCount = 5)
        assertEquals("world", slice.readUtf8())
        assertTrue(slice.exhausted())
    }

    @Test
    fun `slice readUtf8 returns empty when offset exceeds source size`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertEquals("", slice.readUtf8())
    }

    @Test
    fun `slice readUtf8 returns empty after exhaustion`() {
        val parent = source("hi")
        val slice = parent.slice(0, 2)
        slice.readUtf8()
        assertEquals("", slice.readUtf8())
    }

    @Test
    fun `slice readUtf8 after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readUtf8() }
    }

    @Test
    fun `slice readUtf8 with byteCount zero returns empty string`() {
        val parent = source("hello")
        val slice = parent.slice(0, 5)
        assertEquals("", slice.readUtf8(0))
    }

    @Test
    fun `slice readUtf8 with byteCount zero on out-of-range slice returns empty string`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertEquals("", slice.readUtf8(0))
    }

    @Test
    fun `slice readUtf8 with positive byteCount on out-of-range slice throws EOFException`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertFailsWith<EOFException> { slice.readUtf8(1) }
    }

    @Test
    fun `slice readUtf8 with byteCount exceeding window throws EOFException`() {
        val parent = source("hello")
        val slice = parent.slice(offset = 0, byteCount = 3)
        assertFailsWith<EOFException> { slice.readUtf8(5) }
    }

    @Test
    fun `slice readUtf8 with negative byteCount throws IllegalArgumentException`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { slice.readUtf8(-1) }
    }

    @Test
    fun `slice readUtf8 with byteCount after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readUtf8(2) }
    }

    // ----- readUtf8Line boundaries -----

    @Test
    fun `slice readUtf8Line returns line up to bare LF`() {
        val parent = source("foo\nbar")
        val slice = parent.slice(offset = 0, byteCount = 4)
        assertEquals("foo", slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line handles CR LF terminator`() {
        val parent = source("foo\r\nbar")
        val slice = parent.slice(offset = 0, byteCount = 5)
        assertEquals("foo", slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line treats lone CR not followed by LF as part of line`() {
        // Behavior per spec: a trailing `\r` that never met `\n` is kept as part of the line.
        val parent = source("foo\rbar\n")
        val slice = parent.slice(offset = 0, byteCount = "foo\rbar\n".length.toLong())
        // The first call reads up to `\n`: "foo\rbar" (the `\r` is preserved).
        assertEquals("foo\rbar", slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line returns line without terminator at EOF`() {
        val parent = source("only-line")
        val slice = parent.slice(offset = 0, byteCount = 9)
        assertEquals("only-line", slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line returns null at EOF`() {
        val parent = source(ByteArray(0))
        val slice = parent.slice(offset = 0, byteCount = 5)
        assertNull(slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line returns null when offset exceeds source size`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertNull(slice.readUtf8Line())
    }

    @Test
    fun `slice readUtf8Line with trailing CR at very end of window`() {
        // Window cuts off mid-CR — the `\r` is kept as the line's last char.
        val parent = source("foo\r")
        val slice = parent.slice(offset = 0, byteCount = 4)
        assertEquals("foo\r", slice.readUtf8Line())
    }

    // ----- readString -----

    @Test
    fun `slice readString decodes with charset`() {
        val parent = source("héllo".toByteArray(Charsets.ISO_8859_1))
        val slice = parent.slice(offset = 0, byteCount = 5)
        assertEquals("héllo", slice.readString(Charsets.ISO_8859_1))
    }

    @Test
    fun `slice readString returns empty when offset exceeds source size`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertEquals("", slice.readString(Charsets.UTF_8))
    }

    @Test
    fun `slice readString returns empty when window exhausted`() {
        val parent = source("hi")
        val slice = parent.slice(0, 2)
        slice.readUtf8()
        assertEquals("", slice.readString(Charsets.UTF_8))
    }

    @Test
    fun `slice readString after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.readString(Charsets.UTF_8) }
    }

    // ----- peek -----

    @Test
    fun `slice peek after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.peek() }
    }

    // ----- inputStream -----

    @Test
    fun `slice inputStream after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.inputStream() }
    }

    @Test
    fun `slice inputStream single byte read`() {
        val parent = source(byteArrayOf(0x41, 0x42, 0x43))
        val slice = parent.slice(0, 3)
        val s = slice.inputStream()
        assertEquals(0x41, s.read())
        assertEquals(0x42, s.read())
        assertEquals(0x43, s.read())
        assertEquals(-1, s.read())
    }

    @Test
    fun `slice inputStream byteCount zero read returns 0`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        val s = slice.inputStream()
        assertEquals(0, s.read(ByteArray(4), 0, 0))
    }

    @Test
    fun `slice inputStream when offset exceeds source returns -1 from read`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        val s = slice.inputStream()
        assertEquals(-1, s.read())
        // Reading into a buffer also returns -1 immediately.
        assertEquals(-1, s.read(ByteArray(4)))
    }

    @Test
    fun `slice inputStream reads return -1 after window exhausted`() {
        val parent = source("xx")
        val slice = parent.slice(0, 2)
        val s = slice.inputStream()
        assertEquals('x'.code, s.read())
        assertEquals('x'.code, s.read())
        assertEquals(-1, s.read())
        assertEquals(-1, s.read(ByteArray(4)))
    }

    @Test
    fun `slice inputStream close marks slice closed and prevents further reads`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        val s = slice.inputStream()
        s.close()
        // The slice is closed; subsequent reads through the InputStream throw IOException.
        assertFailsWith<IOException> { s.read() }
        assertFailsWith<IOException> { s.read(ByteArray(4), 0, 4) }
    }

    @Test
    fun `slice inputStream after parent close throws IOException on read`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        val s = slice.inputStream()
        parent.close()
        assertFailsWith<IOException> { s.read() }
        assertFailsWith<IOException> { s.read(ByteArray(4), 0, 4) }
    }

    // ----- skip variants -----

    @Test
    fun `slice skip negative byteCount throws IllegalArgumentException`() {
        val parent = source("abc")
        val slice = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { slice.skip(-1) }
    }

    @Test
    fun `slice skip zero when offset exceeds source is no-op`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        // skip(0) should not throw even when realizeOffset reports EOF.
        slice.skip(0)
    }

    @Test
    fun `slice skip positive when offset exceeds source throws EOFException`() {
        val parent = source("abc")
        val slice = parent.slice(offset = 100, byteCount = 5)
        assertFailsWith<EOFException> { slice.skip(1) }
    }

    @Test
    fun `slice skip after close throws IllegalStateException`() {
        val parent = source("data")
        val slice = parent.slice(0, 4)
        slice.close()
        assertFailsWith<IllegalStateException> { slice.skip(1) }
    }

    // ----- nested slice argument validation -----

    @Test
    fun `nested slice rejects negative offset`() {
        val parent = source("abc")
        val outer = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { outer.slice(-1, 1) }
    }

    @Test
    fun `nested slice rejects negative byteCount`() {
        val parent = source("abc")
        val outer = parent.slice(0, 3)
        assertFailsWith<IllegalArgumentException> { outer.slice(0, -1) }
    }

    @Test
    fun `slice on closed slice throws IllegalStateException`() {
        val parent = source("data")
        val outer = parent.slice(0, 4)
        outer.close()
        assertFailsWith<IllegalStateException> { outer.slice(0, 2) }
    }

    // ----- inputStream constructor / non-empty cases -----

    @Test
    fun `slice inputStream read into byte array reads bytes within window`() {
        val parent = source("hello-world")
        val slice = parent.slice(offset = 6, byteCount = 5)
        val s = slice.inputStream()
        val out = ByteArray(10)
        val n = s.read(out, 0, 10)
        assertEquals(5, n)
        assertContentEquals("world".toByteArray(), out.copyOfRange(0, 5))
    }

    // ----- Slice over a source from an InputStream -----

    @Test
    fun `slice over OkioBufferedSource from input stream works`() {
        // Provider source from an InputStream is an OkioBufferedSource, so this exercises
        // OkioBufferedSource.slice() and the resulting SlicedOkioBufferedSource.
        val src = OkioIoProvider.source(ByteArrayInputStream("payload-data".toByteArray()))
        val slice = src.slice(offset = 8, byteCount = 4)
        assertEquals("data", slice.readUtf8())
    }

    // ----- A minimal non-Okio Buffer used to force the slow path -----

    private class NonOkioBufferForSlice : Buffer {
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

        override val size: Long get() = (materialize().size - read).toLong()
        override fun snapshot(): ByteArray {
            val arr = materialize()
            val rem = ByteArray(arr.size - read)
            System.arraycopy(arr, read, rem, 0, rem.size)
            return rem
        }
        override fun clear() { store.reset(); contents = null; read = 0 }
        override fun copyTo(out: Buffer, offset: Long, byteCount: Long): Buffer {
            out.write(materialize(), read + offset.toInt(), byteCount.toInt())
            return this
        }
        override val buffer: Buffer get() = this
        override fun exhausted(): Boolean = size == 0L
        override fun readByte(): Byte = materialize()[read++]
        override fun readByteArray(): ByteArray { val r = snapshot(); read = materialize().size; return r }
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
        override fun readString(charset: Charset): String = String(readByteArray(), charset)
        override fun peek(): BufferedSource = throw UnsupportedOperationException()
        override fun inputStream(): java.io.InputStream = throw UnsupportedOperationException()
        override fun skip(byteCount: Long) { read += byteCount.toInt() }
        override fun slice(offset: Long, byteCount: Long): BufferedSource = throw UnsupportedOperationException()
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (exhausted()) return -1L
            val avail = minOf(byteCount, size)
            sink.write(readByteArray(avail))
            return avail
        }
        override fun write(source: ByteArray): org.dexpace.sdk.core.io.BufferedSink {
            store.write(source); contents = null; return this
        }
        override fun write(source: ByteArray, offset: Int, byteCount: Int): org.dexpace.sdk.core.io.BufferedSink {
            store.write(source, offset, byteCount); contents = null; return this
        }
        override fun writeAll(source: org.dexpace.sdk.core.io.Source): Long = 0L
        override fun writeUtf8(string: String): org.dexpace.sdk.core.io.BufferedSink {
            write(string.toByteArray(Charsets.UTF_8)); return this
        }
        override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): org.dexpace.sdk.core.io.BufferedSink {
            write(string.substring(beginIndex, endIndex).toByteArray(Charsets.UTF_8)); return this
        }
        override fun writeString(string: String, charset: Charset): org.dexpace.sdk.core.io.BufferedSink {
            write(string.toByteArray(charset)); return this
        }
        override fun outputStream(): java.io.OutputStream = throw UnsupportedOperationException()
        override fun emit(): org.dexpace.sdk.core.io.BufferedSink = this
        override fun write(source: Buffer, byteCount: Long) {
            write(source.readByteArray(byteCount))
        }
        override fun flush() {}
        override fun close() {}
    }
}
