package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ETagTest {
    @Test
    fun `strong emits quote-wrapped opaque`() {
        assertEquals("\"xyz\"", ETag.strong("xyz").toHeaderValue())
    }

    @Test
    fun `weak emits W-prefixed quote-wrapped opaque`() {
        assertEquals("W/\"xyz\"", ETag.weak("xyz").toHeaderValue())
    }

    @Test
    fun `ANY emits star`() {
        assertEquals("*", ETag.ANY.toHeaderValue())
    }

    @Test
    fun `strong flags`() {
        val tag = ETag.strong("xyz")
        assertTrue(tag.isStrong)
        assertFalse(tag.isWeak)
        assertFalse(tag.isAny)
    }

    @Test
    fun `weak flags`() {
        val tag = ETag.weak("xyz")
        assertTrue(tag.isWeak)
        assertFalse(tag.isStrong)
        assertFalse(tag.isAny)
    }

    @Test
    fun `ANY flags`() {
        assertTrue(ETag.ANY.isAny)
        assertFalse(ETag.ANY.isStrong)
        assertFalse(ETag.ANY.isWeak)
    }

    @Test
    fun `strong opaque strips quotes`() {
        assertEquals("xyz", ETag.strong("xyz").opaque)
    }

    @Test
    fun `weak opaque strips W prefix and quotes`() {
        assertEquals("xyz", ETag.weak("xyz").opaque)
    }

    @Test
    fun `ANY opaque is star`() {
        assertEquals("*", ETag.ANY.opaque)
    }

    @Test
    fun `parse strong form returns strong`() {
        val parsed = ETag.parse("\"xyz\"")
        assertEquals(ETag.strong("xyz"), parsed)
    }

    @Test
    fun `parse weak form returns weak`() {
        val parsed = ETag.parse("W/\"xyz\"")
        assertEquals(ETag.weak("xyz"), parsed)
    }

    @Test
    fun `parse star returns ANY`() {
        assertEquals(ETag.ANY, ETag.parse("*"))
    }

    @Test
    fun `parse null returns null`() {
        assertNull(ETag.parse(null))
    }

    @Test
    fun `parse empty returns null`() {
        assertNull(ETag.parse(""))
    }

    @Test
    fun `parse blank returns null`() {
        assertNull(ETag.parse("   "))
    }

    @Test
    fun `parse malformed throws`() {
        val ex = assertFailsWith<IllegalArgumentException> { ETag.parse("malformed") }
        assertEquals(true, ex.message!!.contains("malformed"))
    }

    @Test
    fun `parse unterminated quote throws`() {
        assertFailsWith<IllegalArgumentException> { ETag.parse("\"xyz") }
    }

    @Test
    fun `strong with empty opaque is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { ETag.strong("") }
        assertEquals(true, ex.message!!.contains("empty"))
    }

    @Test
    fun `weak with empty opaque is allowed per RFC 7232`() {
        // W/"" is a valid (if unusual) weak validator. RFC 7232 §2.3 permits zero-length
        // opaque-tag values; we accept them rather than impose a stricter rule.
        val tag = ETag.weak("")
        assertEquals("W/\"\"", tag.toHeaderValue())
        assertTrue(tag.isWeak)
    }

    @Test
    fun `parse and re-emit round-trips for strong`() {
        val original = ETag.strong("x")
        val parsed = ETag.parse(original.toHeaderValue())
        assertEquals(original, parsed)
    }

    @Test
    fun `parse and re-emit round-trips for weak`() {
        val original = ETag.weak("x")
        val parsed = ETag.parse(original.toHeaderValue())
        assertEquals(original, parsed)
    }

    @Test
    fun `parse trims whitespace`() {
        assertEquals(ETag.strong("x"), ETag.parse("  \"x\"  "))
    }

    @Test
    fun `toString returns raw header value`() {
        assertEquals("\"xyz\"", ETag.strong("xyz").toString())
        assertEquals("W/\"xyz\"", ETag.weak("xyz").toString())
        assertEquals("*", ETag.ANY.toString())
    }

    @Test
    fun `equal inline-class instances compare equal and share hashCode`() {
        val a = ETag.strong("xyz")
        val b = ETag.strong("xyz")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `weak and strong with the same opaque are not equal`() {
        val weak = ETag.weak("xyz")
        val strong = ETag.strong("xyz")
        // Touch the equality path for the inline-class — the underlying raw values differ.
        assertEquals(false, weak == strong)
    }

    @Test
    fun `ANY singleton compares equal to itself but unequal to a concrete tag`() {
        @Suppress("KotlinConstantConditions")
        assertEquals(ETag.ANY, ETag.ANY)
        assertEquals(false, ETag.ANY == ETag.strong("x"))
    }

    @Test
    fun `parse with extra whitespace around star returns ANY`() {
        // The trimming branch is touched here.
        assertEquals(ETag.ANY, ETag.parse("  *  "))
    }

    @Test
    fun `parse rejects W only with no quotes`() {
        // Tests the negative branch of the weak-form length/prefix check.
        assertFailsWith<IllegalArgumentException> { ETag.parse("W/") }
    }

    @Test
    fun `parse rejects single quote character`() {
        // Length 1 — fails both minimum-length checks for strong and weak.
        assertFailsWith<IllegalArgumentException> { ETag.parse("\"") }
    }

    @Test
    fun `parse rejects W slash quote with no closing quote`() {
        assertFailsWith<IllegalArgumentException> { ETag.parse("W/\"") }
    }
}
