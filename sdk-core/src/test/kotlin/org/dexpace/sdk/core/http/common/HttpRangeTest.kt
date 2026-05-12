package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpRangeTest {

    @Test
    fun `bytes(0, 1024) emits closed range from zero`() {
        assertEquals("bytes=0-1023", HttpRange.bytes(0, 1024).toHeaderValue())
    }

    @Test
    fun `bytes(100, 50) emits offset closed range`() {
        assertEquals("bytes=100-149", HttpRange.bytes(100, 50).toHeaderValue())
    }

    @Test
    fun `bytes with zero length is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { HttpRange.bytes(0, 0) }
        assertEquals(true, ex.message!!.contains("positive"))
    }

    @Test
    fun `bytes with negative offset is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { HttpRange.bytes(-1, 100) }
        assertEquals(true, ex.message!!.contains("non-negative"))
    }

    @Test
    fun `bytes overflows on offset + length`() {
        // offset + (length - 1) = Long.MAX_VALUE + 9 → overflow detected by Math.addExact.
        assertFailsWith<ArithmeticException> { HttpRange.bytes(Long.MAX_VALUE, 10) }
    }

    @Test
    fun `suffix(500) emits negative-prefixed length`() {
        assertEquals("bytes=-500", HttpRange.suffix(500).toHeaderValue())
    }

    @Test
    fun `suffix with zero length is rejected`() {
        assertFailsWith<IllegalArgumentException> { HttpRange.suffix(0) }
    }

    @Test
    fun `suffix with negative length is rejected`() {
        assertFailsWith<IllegalArgumentException> { HttpRange.suffix(-1) }
    }

    @Test
    fun `from(1000) emits open-ended range`() {
        assertEquals("bytes=1000-", HttpRange.from(1000).toHeaderValue())
    }

    @Test
    fun `from with negative offset is rejected`() {
        assertFailsWith<IllegalArgumentException> { HttpRange.from(-1) }
    }

    @Test
    fun `parse round-trips canonical form`() {
        val parsed = HttpRange.parse("bytes=0-1023")
        assertEquals("bytes=0-1023", parsed.toHeaderValue())
    }

    @Test
    fun `parse accepts uppercase unit prefix`() {
        // RFC 7233 says the unit token is case-insensitive; the textual form is preserved
        // so consumers that expect echoed case get it.
        val parsed = HttpRange.parse("BYTES=0-1023")
        assertEquals("BYTES=0-1023", parsed.toHeaderValue())
    }

    @Test
    fun `parse rejects non-bytes unit`() {
        val ex = assertFailsWith<IllegalArgumentException> { HttpRange.parse("range=0-1023") }
        assertEquals(true, ex.message!!.contains("bytes"))
    }

    @Test
    fun `parse rejects multi-range`() {
        val ex = assertFailsWith<IllegalArgumentException> { HttpRange.parse("bytes=0-100,200-300") }
        assertEquals(true, ex.message!!.contains("multi-range"))
    }

    @Test
    fun `toString returns header value`() {
        assertEquals("bytes=0-1023", HttpRange.bytes(0, 1024).toString())
    }
}
