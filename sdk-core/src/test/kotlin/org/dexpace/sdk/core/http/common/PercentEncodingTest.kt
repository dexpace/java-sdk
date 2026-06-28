/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the [PercentEncoding] helper — the shared RFC 3986 URL-component
 * percent-encoder used by [QueryParams] (query names/values) and operation path segments.
 *
 * The defining property versus `application/x-www-form-urlencoded` is space and `+`
 * handling: a space encodes to `%20` (not `+`), and a literal `+` is preserved as `%2B`
 * (it is **not** treated as a space on the way back).
 */
class PercentEncodingTest {
    @Test
    fun `encode renders a space as percent-20 not plus`() {
        assertEquals("a%20b", PercentEncoding.encodeComponent("a b"))
    }

    @Test
    fun `encode renders a literal plus as percent-2B`() {
        assertEquals("a%2Bb", PercentEncoding.encodeComponent("a+b"))
    }

    @Test
    fun `encode percent-encodes the structural delimiters and slash`() {
        assertEquals("a%26b", PercentEncoding.encodeComponent("a&b"))
        assertEquals("a%3Db", PercentEncoding.encodeComponent("a=b"))
        assertEquals("a%2Fb", PercentEncoding.encodeComponent("a/b"))
        assertEquals("a%23b", PercentEncoding.encodeComponent("a#b"))
    }

    @Test
    fun `encode leaves unreserved ascii untouched`() {
        assertEquals("Abc-123_x.y", PercentEncoding.encodeComponent("Abc-123_x.y"))
    }

    @Test
    fun `encode leaves the unreserved tilde untouched`() {
        // '~' is in the RFC 3986 unreserved set; URLEncoder over-encodes it to %7E, which the
        // codec corrects back to '~'.
        assertEquals("~", PercentEncoding.encodeComponent("~"))
        assertEquals("~user", PercentEncoding.encodeComponent("~user"))
    }

    @Test
    fun `encode percent-encodes the reserved asterisk`() {
        // '*' is a sub-delimiter (reserved), not unreserved; URLEncoder leaves it raw, which the
        // codec corrects to %2A so a component value cannot smuggle a reserved character.
        assertEquals("%2A", PercentEncoding.encodeComponent("*"))
        assertEquals("a%2Ab", PercentEncoding.encodeComponent("a*b"))
    }

    @Test
    fun `encode then decode round-trips the corrected tilde and asterisk`() {
        val original = "~a*b"
        assertEquals("~a%2Ab", PercentEncoding.encodeComponent(original))
        assertEquals(original, PercentEncoding.decodeComponent(PercentEncoding.encodeComponent(original)))
    }

    @Test
    fun `encode percent-encodes multibyte utf8 as utf8 bytes`() {
        assertEquals("%C3%A9", PercentEncoding.encodeComponent("é")) // é
    }

    @Test
    fun `encode of empty string is empty`() {
        assertEquals("", PercentEncoding.encodeComponent(""))
    }

    @Test
    fun `decode turns percent-20 into a space`() {
        assertEquals("a b", PercentEncoding.decodeComponent("a%20b"))
    }

    @Test
    fun `decode keeps a literal plus as a plus and does not turn it into a space`() {
        assertEquals("a+b", PercentEncoding.decodeComponent("a+b"))
    }

    @Test
    fun `decode turns percent-2B into a literal plus`() {
        assertEquals("a+b", PercentEncoding.decodeComponent("a%2Bb"))
    }

    @Test
    fun `decode reconstructs multibyte utf8`() {
        assertEquals("é", PercentEncoding.decodeComponent("%C3%A9"))
    }

    @Test
    fun `decode keeps raw text on malformed percent-encoding rather than throwing`() {
        assertEquals("%zz", PercentEncoding.decodeComponent("%zz"))
        assertEquals("%2", PercentEncoding.decodeComponent("%2"))
    }

    @Test
    fun `decode keeps a literal plus even when the rest is malformed`() {
        assertEquals("a+%zz", PercentEncoding.decodeComponent("a+%zz"))
    }

    @Test
    fun `encode then decode round-trips spaces plus slashes and unicode`() {
        val original = "hello world+1/2=3 é"
        assertEquals(original, PercentEncoding.decodeComponent(PercentEncoding.encodeComponent(original)))
    }
}
