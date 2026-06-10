/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTypeTest {
    // ---- parse ------------------------------------------------------------------

    @Test
    fun `parse splits type and subtype, no params`() {
        val mt = MediaType.parse("text/plain")
        assertEquals("text", mt.type)
        assertEquals("plain", mt.subtype)
        assertTrue(mt.parameters.isEmpty())
    }

    @Test
    fun `parse lower-cases type and subtype`() {
        val mt = MediaType.parse("Application/JSON")
        assertEquals("application", mt.type)
        assertEquals("json", mt.subtype)
    }

    @Test
    fun `parse extracts a single parameter`() {
        val mt = MediaType.parse("text/plain; charset=utf-8")
        assertEquals(mapOf("charset" to "utf-8"), mt.parameters)
    }

    @Test
    fun `parse extracts multiple parameters`() {
        val mt = MediaType.parse("application/json; q=0.8; charset=utf-8")
        assertEquals(mapOf("q" to "0.8", "charset" to "utf-8"), mt.parameters)
    }

    @Test
    fun `parse preserves param values that contain equals signs`() {
        // Common case: multipart boundary that contains `=` characters. The first `=`
        // is the key/value separator and any subsequent ones must be preserved.
        val mt = MediaType.parse("multipart/form-data; boundary=abc=def")
        assertEquals("abc=def", mt.parameters["boundary"])
    }

    @Test
    fun `parse fails on blank input`() {
        assertFailsWith<IllegalArgumentException> { MediaType.parse("") }
        assertFailsWith<IllegalArgumentException> { MediaType.parse("   ") }
    }

    @Test
    fun `parse fails when slash is missing`() {
        val ex = assertFailsWith<IllegalArgumentException> { MediaType.parse("bad") }
        assertTrue(ex.message!!.contains("bad"))
    }

    @Test
    fun `parse fails when type is empty`() {
        // `/plain` — slashIndex is 0, so the check `slashIndex <= 0` rejects it.
        assertFailsWith<IllegalArgumentException> { MediaType.parse("/plain") }
    }

    @Test
    fun `parse fails when subtype is empty`() {
        // `text/` — slashIndex is mimeString.length-1, so it's rejected.
        assertFailsWith<IllegalArgumentException> { MediaType.parse("text/") }
    }

    @Test
    fun `parse fails on wildcard type with concrete subtype`() {
        assertFailsWith<IllegalArgumentException> { MediaType.parse("*/json") }
    }

    @Test
    fun `parse fails on parameter without equals sign`() {
        assertFailsWith<IllegalArgumentException> { MediaType.parse("a/b; c") }
    }

    @Test
    fun `parse fails on parameter with empty key`() {
        assertFailsWith<IllegalArgumentException> { MediaType.parse("a/b; =value") }
    }

    @Test
    fun `parse fails on parameter with empty value`() {
        assertFailsWith<IllegalArgumentException> { MediaType.parse("a/b; key=") }
    }

    @Test
    fun `parse skips blank parameter segments between semicolons`() {
        val mt = MediaType.parse("text/plain; ; charset=utf-8 ; ")
        assertEquals(mapOf("charset" to "utf-8"), mt.parameters)
    }

    @Test
    fun `parse preserves parameter value case (boundary is case-sensitive)`() {
        val mt = MediaType.parse("multipart/form-data; boundary=AbCdEf")
        assertEquals("AbCdEf", mt.parameters["boundary"])
    }

    @Test
    fun `parse strips outer quotes from quoted parameter values`() {
        // charset="utf-8" → charset value should be utf-8 (unquoted)
        val mt = MediaType.parse("text/plain; charset=\"utf-8\"")
        assertEquals("utf-8", mt.parameters["charset"])
    }

    @Test
    fun `charset resolves correctly from a quoted parameter value`() {
        val mt = MediaType.parse("text/plain; charset=\"utf-8\"")
        assertEquals(java.nio.charset.StandardCharsets.UTF_8, mt.charset)
    }

    @Test
    fun `parse unescapes quoted-pair sequences in parameter values`() {
        // boundary="abc\"def" → unescaped value is abc"def
        val mt = MediaType.parse("multipart/form-data; boundary=\"abc\\\"def\"")
        assertEquals("abc\"def", mt.parameters["boundary"])
    }

    @Test
    fun `parse unescapes backslash sequences in parameter values`() {
        // boundary="abc\\def" → unescaped value is abc\def
        val mt = MediaType.parse("multipart/form-data; boundary=\"abc\\\\def\"")
        assertEquals("abc\\def", mt.parameters["boundary"])
    }

    @Test
    fun `parse accepts wildcard-wildcard`() {
        val mt = MediaType.parse("*/*")
        assertEquals("*", mt.type)
        assertEquals("*", mt.subtype)
    }

    // ---- of factory -------------------------------------------------------------

    @Test
    fun `of normalises type and subtype to lower case`() {
        val mt = MediaType.of("Application", "JSON")
        assertEquals("application", mt.type)
        assertEquals("json", mt.subtype)
        assertTrue(mt.parameters.isEmpty())
    }

    @Test
    fun `of accepts an explicit parameter map`() {
        // `of` lower-cases parameter keys but preserves the original value casing.
        // `parse` also preserves value casing — both factories are consistent on this.
        val mt = MediaType.of("text", "plain", mapOf("Charset" to "UTF-8"))
        assertEquals("UTF-8", mt.parameters["charset"])
    }

    @Test
    fun `of rejects blank type`() {
        assertFailsWith<IllegalArgumentException> { MediaType.of("", "json") }
        assertFailsWith<IllegalArgumentException> { MediaType.of("   ", "json") }
    }

    @Test
    fun `of rejects blank subtype`() {
        assertFailsWith<IllegalArgumentException> { MediaType.of("text", "") }
        assertFailsWith<IllegalArgumentException> { MediaType.of("text", "  ") }
    }

    @Test
    fun `of rejects wildcard type with concrete subtype`() {
        assertFailsWith<IllegalArgumentException> { MediaType.of("*", "plain") }
    }

    @Test
    fun `of accepts wildcard-wildcard`() {
        val mt = MediaType.of("*", "*")
        assertEquals("*", mt.type)
        assertEquals("*", mt.subtype)
    }

    // ---- charset accessor -------------------------------------------------------

    @Test
    fun `charset resolves a known charset name`() {
        val mt = MediaType.parse("text/plain; charset=utf-8")
        assertEquals(StandardCharsets.UTF_8, mt.charset)
    }

    @Test
    fun `charset is null when parameter is absent`() {
        val mt = MediaType.parse("text/plain")
        assertNull(mt.charset)
    }

    @Test
    fun `charset is null when parameter value is unknown`() {
        val mt = MediaType.of("text", "plain", mapOf("charset" to "not-a-charset"))
        assertNull(mt.charset)
    }

    // ---- includes ---------------------------------------------------------------

    @Test
    fun `wildcard subtype includes concrete subtype of same type`() {
        val pattern = MediaType.parse("text/*")
        assertTrue(pattern.includes(MediaType.parse("text/plain")))
        assertTrue(pattern.includes(MediaType.parse("text/html")))
    }

    @Test
    fun `wildcard-wildcard includes any concrete media type`() {
        val any = MediaType.parse("*/*")
        assertTrue(any.includes(MediaType.parse("text/plain")))
        assertTrue(any.includes(MediaType.parse("application/json")))
    }

    @Test
    fun `concrete media type does not include a different concrete media type`() {
        val a = MediaType.parse("text/plain")
        val b = MediaType.parse("text/html")
        assertFalse(a.includes(b))
    }

    @Test
    fun `different types do not include each other (typeMatches false branch)`() {
        // typeMatches=false branch in `includes`.
        val a = MediaType.parse("text/plain")
        val b = MediaType.parse("application/json")
        assertFalse(a.includes(b))
        assertFalse(b.includes(a))
    }

    @Test
    fun `concrete media type does not include a wildcard receiver in reverse direction`() {
        // text/plain does not include text/*, even though text/* includes text/plain.
        val plain = MediaType.parse("text/plain")
        val anyText = MediaType.parse("text/*")
        assertFalse(plain.includes(anyText))
    }

    @Test
    fun `includes ignores parameters`() {
        val a = MediaType.parse("text/plain; charset=utf-8")
        val b = MediaType.parse("text/plain")
        assertTrue(a.includes(b))
        assertTrue(b.includes(a))
    }

    @Test
    fun `includes is case-insensitive on type and subtype`() {
        val a = MediaType.parse("Text/Plain")
        val b = MediaType.of("TEXT", "PLAIN")
        assertTrue(a.includes(b))
    }

    // ---- fullType ---------------------------------------------------------------

    @Test
    fun `fullType returns type slash subtype`() {
        assertEquals("text/plain", MediaType.parse("text/plain").fullType)
        assertEquals("application/json", MediaType.parse("application/json; charset=utf-8").fullType)
    }

    // ---- toString ---------------------------------------------------------------

    @Test
    fun `toString omits semicolon when no parameters`() {
        assertEquals("text/plain", MediaType.parse("text/plain").toString())
    }

    @Test
    fun `toString joins parameters with semicolons`() {
        val mt = MediaType.parse("text/plain; charset=utf-8")
        assertEquals("text/plain;charset=utf-8", mt.toString())
    }

    @Test
    fun `toString round-trips through parse for canonical form`() {
        val original = MediaType.parse("application/json;charset=utf-8")
        val reparsed = MediaType.parse(original.toString())
        assertEquals(original, reparsed)
    }

    @Test
    fun `toString quotes a parameter value containing a separator`() {
        // A boundary containing `;` must be quoted, otherwise the `;` re-parses as the start
        // of a new parameter and the value is corrupted on the wire.
        val mt = MediaType.of("multipart", "form-data", mapOf("boundary" to "a;b"))
        assertEquals("multipart/form-data;boundary=\"a;b\"", mt.toString())
        assertEquals(mt, MediaType.parse(mt.toString()))
        assertEquals("a;b", MediaType.parse(mt.toString()).parameters["boundary"])
    }

    @Test
    fun `toString quotes a parameter value containing whitespace`() {
        val mt = MediaType.of("text", "plain", mapOf("title" to "hello world"))
        assertEquals("text/plain;title=\"hello world\"", mt.toString())
        assertEquals(mt, MediaType.parse(mt.toString()))
    }

    @Test
    fun `toString quotes an empty parameter value`() {
        // An empty value cannot be emitted bare (parse rejects an empty rawValue); it must
        // be quoted so the original (empty) value survives a round trip.
        val mt = MediaType.of("text", "plain", mapOf("x" to ""))
        assertEquals("text/plain;x=\"\"", mt.toString())
        assertEquals(mt, MediaType.parse(mt.toString()))
        assertEquals("", MediaType.parse(mt.toString()).parameters["x"])
    }

    @Test
    fun `toString escapes quote and backslash characters in a parameter value`() {
        val mt = MediaType.of("multipart", "form-data", mapOf("boundary" to "a\"b\\c"))
        // Each `"` and `\` is emitted as a quoted-pair inside the quoted-string.
        assertEquals("multipart/form-data;boundary=\"a\\\"b\\\\c\"", mt.toString())
        assertEquals(mt, MediaType.parse(mt.toString()))
        assertEquals("a\"b\\c", MediaType.parse(mt.toString()).parameters["boundary"])
    }

    @Test
    fun `toString leaves a bare token value unquoted`() {
        // A value made entirely of RFC 7230 tchar characters needs no quoting.
        val mt = MediaType.of("application", "json", mapOf("charset" to "utf-8"))
        assertEquals("application/json;charset=utf-8", mt.toString())
    }

    @Test
    fun `parse-toString round-trips for every constructible parameter value`() {
        val tricky =
            listOf(
                "simple-token",
                "a;b",
                "a=b",
                "hello world",
                "with\"quote",
                "with\\backslash",
                "with,comma",
                "tab\there",
                "",
                "AbCdEf",
            )
        for (value in tricky) {
            val mt = MediaType.of("multipart", "form-data", mapOf("boundary" to value))
            val reparsed = MediaType.parse(mt.toString())
            assertEquals(value, reparsed.parameters["boundary"], "round trip failed for value [$value]")
            assertEquals(mt, reparsed, "MediaType inequality after round trip for value [$value]")
        }
    }

    // ---- equality + hashCode ----------------------------------------------------

    @Test
    fun `equality is case-insensitive via normalisation`() {
        val a = MediaType.parse("Text/Plain")
        val b = MediaType.parse("text/plain")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equality requires identical parameters`() {
        val a = MediaType.parse("text/plain; charset=utf-8")
        val b = MediaType.parse("text/plain")
        assertFalse(a == b)
    }

    @Test
    fun `parameters are also lower-cased on the key`() {
        val mt = MediaType.of("text", "plain", mapOf("Charset" to "utf-8"))
        assertEquals(mapOf("charset" to "utf-8"), mt.parameters)
    }
}
