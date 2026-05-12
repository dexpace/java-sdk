package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Io
import java.io.EOFException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
