/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadersTest {
    @Test
    fun `string-set values are visible via typed get`() {
        val headers =
            Headers.builder()
                .set("authorization", "Bearer abc")
                .build()

        // The String API stores with case-insensitive normalisation; the typed API must
        // see the same value because both use the same lower-cased internal key.
        assertEquals("Bearer abc", headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `typed-set values are visible via string get`() {
        val headers =
            Headers.builder()
                .set(HttpHeaderName.CONTENT_TYPE, "application/json")
                .build()

        assertEquals("application/json", headers.get("Content-Type"))
        assertEquals("application/json", headers.get("content-type"))
    }

    @Test
    fun `add accumulates multiple values for the same typed name`() {
        val headers =
            Headers.builder()
                .set(HttpHeaderName.SET_COOKIE, "a=1")
                .add(HttpHeaderName.SET_COOKIE, "b=2")
                .build()

        val values = headers.values(HttpHeaderName.SET_COOKIE)
        assertEquals(listOf("a=1", "b=2"), values)
        // First-value lookup picks the earliest entry.
        assertEquals("a=1", headers.get(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `set with null value removes the header (string overload)`() {
        val headers =
            Headers.builder()
                .set("X-Trace-Id", "trace-1")
                .set("X-Trace-Id", null)
                .build()

        assertFalse(headers.contains("X-Trace-Id"))
        assertNull(headers.get("X-Trace-Id"))
    }

    @Test
    fun `set with null value removes the header (typed overload)`() {
        val headers =
            Headers.builder()
                .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
                .set(HttpHeaderName.AUTHORIZATION, null)
                .build()

        assertFalse(headers.contains(HttpHeaderName.AUTHORIZATION))
        assertNull(headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `set replaces previously accumulated values`() {
        val headers =
            Headers.builder()
                .add(HttpHeaderName.SET_COOKIE, "a=1")
                .add(HttpHeaderName.SET_COOKIE, "b=2")
                .set(HttpHeaderName.SET_COOKIE, "c=3")
                .build()

        assertEquals(listOf("c=3"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `addAll merges values from another Headers instance`() {
        val a =
            Headers.builder()
                .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
                .add(HttpHeaderName.SET_COOKIE, "a=1")
                .build()
        val b =
            Headers.builder()
                .add(HttpHeaderName.SET_COOKIE, "b=2")
                .set(HttpHeaderName.CONTENT_TYPE, "application/json")
                .build()

        val merged = a.newBuilder().addAll(b).build()

        assertEquals("Bearer abc", merged.get(HttpHeaderName.AUTHORIZATION))
        assertEquals("application/json", merged.get(HttpHeaderName.CONTENT_TYPE))
        // Multi-value names accumulate across the merge — neither side is dropped.
        assertEquals(listOf("a=1", "b=2"), merged.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `contains works for both string and typed names`() {
        val headers =
            Headers.builder()
                .set(HttpHeaderName.AUTHORIZATION, "Bearer abc")
                .build()

        assertTrue(headers.contains("Authorization"))
        assertTrue(headers.contains("authorization"))
        assertTrue(headers.contains(HttpHeaderName.AUTHORIZATION))
        assertFalse(headers.contains("X-Missing"))
        assertFalse(headers.contains(HttpHeaderName.X_REQUEST_ID))
    }

    @Test
    fun `remove with typed name clears all values for that header`() {
        val headers =
            Headers.builder()
                .add(HttpHeaderName.SET_COOKIE, "a=1")
                .add(HttpHeaderName.SET_COOKIE, "b=2")
                .remove(HttpHeaderName.SET_COOKIE)
                .build()

        assertFalse(headers.contains(HttpHeaderName.SET_COOKIE))
        assertTrue(headers.values(HttpHeaderName.SET_COOKIE).isEmpty())
    }

    @Test
    fun `add with list overload appends all values`() {
        val headers =
            Headers.builder()
                .add(HttpHeaderName.SET_COOKIE, listOf("a=1", "b=2"))
                .add(HttpHeaderName.SET_COOKIE, "c=3")
                .build()

        assertEquals(listOf("a=1", "b=2", "c=3"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `set with list overload replaces values`() {
        val headers =
            Headers.builder()
                .add(HttpHeaderName.SET_COOKIE, "old=1")
                .set(HttpHeaderName.SET_COOKIE, listOf("new=1", "new=2"))
                .build()

        assertEquals(listOf("new=1", "new=2"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    // ---- value validation (request/header-splitting guard) ----------------------

    @Test
    fun `add rejects a value containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil", "ok\nInjected: 1")
        }
    }

    @Test
    fun `add rejects a value containing a carriage return`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil", "ok\rInjected: 1")
        }
    }

    @Test
    fun `set rejects a value containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil", "ok\nInjected: 1")
        }
    }

    @Test
    fun `set rejects a value containing a carriage return`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil", "ok\rInjected: 1")
        }
    }

    @Test
    fun `add list overload rejects any value containing CR or LF`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil", listOf("fine", "bad\nvalue"))
        }
    }

    @Test
    fun `set list overload rejects any value containing CR or LF`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil", listOf("bad\rvalue"))
        }
    }

    @Test
    fun `typed add rejects a value containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add(HttpHeaderName.SET_COOKIE, "a=1\nInjected: 1")
        }
    }

    @Test
    fun `typed set rejects a value containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set(HttpHeaderName.SET_COOKIE, "a=1\nInjected: 1")
        }
    }

    @Test
    fun `add rejects a value containing a NUL`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil", "ok" + 0.toChar() + "bad")
        }
    }

    @Test
    fun `add rejects a value containing a DEL control character`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil", "ok" + 127.toChar() + "bad")
        }
    }

    @Test
    fun `value validation rejects interior control bytes through 0x1F and DEL but accepts tab and space`() {
        // Pins the value-side predicate boundary. The policy mirrors the name check — the C0
        // range (up to and including 0x1F) and DEL (0x7F) are rejected — with one deliberate
        // exception: horizontal tab (0x09), which RFC 7230 permits as field-value whitespace.
        // Space (0x20), the first non-control code point, is accepted. Constructed with toChar()
        // so the bytes are unambiguous.
        val nul = 0.toChar() // 0x00, bottom of the C0 range
        val unitSeparator = 31.toChar() // 0x1F, top of the C0 range
        val del = 127.toChar() // 0x7F
        val tab = 9.toChar() // 0x09, the one permitted control character
        val space = 32.toChar() // 0x20, first accepted code point
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo", "a" + nul + "b") }
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo", "a" + unitSeparator + "b") }
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo", "a" + del + "b") }
        val accepted = Headers.builder().add("X-Foo", "a" + tab + space + "b").build()
        assertEquals("a" + tab + space + "b", accepted.get("X-Foo"))
    }

    @Test
    fun `the value rejection message does not echo the value`() {
        // The value may be a secret (Authorization) or oversized; unlike the name, it is never
        // rendered into the error message — only the header name is, to locate the offender.
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                Headers.builder().add("X-Trace-Id", "secret-token" + 0.toChar() + "x")
            }
        val message = thrown.message ?: ""
        assertTrue(message.contains("X-Trace-Id"), "message should name the header, got: $message")
        assertFalse(message.contains("secret-token"), "message must not echo the value")
    }

    @Test
    fun `the rejection message names the offending header`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                Headers.builder().add("X-Trace-Id", "ok\nbad")
            }
        assertTrue(
            thrown.message?.lowercase()?.contains("x-trace-id") == true,
            "message should name the header, got: ${thrown.message}",
        )
    }

    @Test
    fun `normal values without prohibited control characters are accepted`() {
        val headers =
            Headers.builder()
                .add("X-Plain", "hello world")
                .set("Authorization", "Bearer abc.def-ghi")
                .add(HttpHeaderName.SET_COOKIE, "id=42; Path=/")
                // Horizontal tab is the one permitted control character, and UTF-8 is not a control
                // character at all, so both pass the conservative check.
                .set("X-Unicode", "café\tvalue")
                .build()

        assertEquals("hello world", headers.get("X-Plain"))
        assertEquals("Bearer abc.def-ghi", headers.get("Authorization"))
        assertEquals("id=42; Path=/", headers.get(HttpHeaderName.SET_COOKIE))
        assertEquals("café\tvalue", headers.get("X-Unicode"))
    }

    @Test
    fun `set with null still removes even though value validation is in place`() {
        // Removal must not run value validation — null is the documented removal signal.
        val headers =
            Headers.builder()
                .set("X-Trace-Id", "trace-1")
                .set("X-Trace-Id", null)
                .build()

        assertFalse(headers.contains("X-Trace-Id"))
        assertNull(headers.get("X-Trace-Id"))
    }

    // ---- name validation (request/header-splitting guard) -----------------------

    @Test
    fun `add rejects a name containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\nInjected", "v")
        }
    }

    @Test
    fun `add rejects a name containing a carriage return`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\rInjected", "v")
        }
    }

    @Test
    fun `add rejects a name containing CRLF`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\r\nInjected: 1", "v")
        }
    }

    @Test
    fun `add rejects a name containing a NUL`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\u0000Injected", "v")
        }
    }

    @Test
    fun `add rejects a name containing a DEL control character`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\u007FInjected", "v")
        }
    }

    @Test
    fun `add list overload rejects a name containing CR or LF`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("X-Evil\nInjected", listOf("v"))
        }
    }

    @Test
    fun `set rejects a name containing a line feed`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil\nInjected", "v")
        }
    }

    @Test
    fun `set rejects a name containing a carriage return`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil\rInjected", "v")
        }
    }

    @Test
    fun `set list overload rejects a name containing a NUL`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().set("X-Evil\u0000Injected", listOf("v"))
        }
    }

    @Test
    fun `add rejects a blank name`() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("   ", "v")
        }
    }

    @Test
    fun `the name rejection message names the offending header`() {
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                Headers.builder().add("X-Trace-Id\nInjected", "v")
            }
        assertTrue(
            thrown.message?.lowercase()?.contains("x-trace-id") == true,
            "message should name the header, got: ${thrown.message}",
        )
    }

    @Test
    fun `normal names without control characters are accepted`() {
        val headers =
            Headers.builder()
                .add("X-Plain", "a")
                // Surrounding whitespace is trimmed by name normalisation, not rejected.
                .set("  Authorization  ", "Bearer t")
                .build()

        assertEquals("a", headers.get("X-Plain"))
        assertEquals("Bearer t", headers.get("Authorization"))
    }

    @Test
    fun `a surrounding-whitespace control character is trimmed, not rejected`() {
        // Validation runs on the trimmed name: a leading tab or trailing line feed is stripped
        // before it could reach the wire, so it is harmless. Only an interior control character
        // is a splitting vector. Built without escape literals to keep the bytes unambiguous.
        val tab = 9.toChar()
        val lf = 10.toChar()
        val headers =
            Headers.builder()
                .add(tab + "X-Foo" + lf, "v")
                .build()

        assertEquals("v", headers.get("X-Foo"))
    }

    @Test
    fun `the name rejection message escapes control characters instead of echoing them`() {
        val lf = 10.toChar()
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                Headers.builder().add("X-Trace-Id" + lf + "Injected", "v")
            }
        val message = thrown.message ?: ""
        assertFalse(message.contains(lf), "raw control character must not appear in the message")
        assertTrue(message.contains("X-Trace-Id"), "message should still name the offending header")
        val backslash = 92.toChar()
        assertTrue(
            message.contains(backslash + "u000a"),
            "control character should be rendered as a \\uXXXX escape, got: $message",
        )
    }

    @Test
    fun `name validation rejects interior control bytes through 0x1F and DEL but accepts space`() {
        // Pin the predicate boundary the shared validator introduces: an interior byte in the C0
        // range (up to and including 0x1F) and DEL (0x7F) is rejected, while 0x20 (space) — the
        // first non-control code point — is accepted, since the policy is deliberately narrower
        // than RFC 7230's tchar set. Constructed with toChar() so the bytes are unambiguous.
        val tab = 9.toChar() // 0x09, inside the C0 range
        val unitSeparator = 31.toChar() // 0x1F, top of the C0 range
        val del = 127.toChar() // 0x7F
        val space = 32.toChar() // 0x20, first accepted code point
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo" + tab + "Bar", "v") }
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo" + unitSeparator + "Bar", "v") }
        assertFailsWith<IllegalArgumentException> { Headers.builder().add("X-Foo" + del + "Bar", "v") }
        val accepted = Headers.builder().add("X-Foo" + space + "Bar", "v").build()
        assertEquals("v", accepted.get("X-Foo Bar"))
    }

    // ---- accessors & equality coverage ------------------------------------------

    @Test
    fun `names returns every distinct lower-cased header name`() {
        val headers =
            Headers.builder()
                .set("Authorization", "Bearer abc")
                .set("Content-Type", "application/json")
                .add("X-Foo", "1")
                .build()
        val names = headers.names()
        assertTrue(names.contains("authorization"), "expected lower-cased authorization, got $names")
        assertTrue(names.contains("content-type"))
        assertTrue(names.contains("x-foo"))
    }

    @Test
    fun `entries returns name and values list pairs`() {
        val headers =
            Headers.builder()
                .add("X-Foo", "1")
                .add("X-Foo", "2")
                .set("X-Bar", "3")
                .build()
        val map = headers.entries().associate { it.key to it.value }
        assertEquals(listOf("1", "2"), map["x-foo"])
        assertEquals(listOf("3"), map["x-bar"])
    }

    // ---- immutability of accessors (defensive copies) --------------------------

    @Test
    fun `entries set rejects structural mutation`() {
        val headers = Headers.builder().add("X-Foo", "1").build()
        val entries = headers.entries()
        assertFailsWith<UnsupportedOperationException> {
            (entries as MutableSet<Map.Entry<String, List<String>>>).clear()
        }
        // A live LinkedHashMap.entries view would have honoured this and mutated the source.
        assertTrue(headers.contains("X-Foo"))
        assertEquals(listOf("1"), headers.values("X-Foo"))
    }

    @Test
    fun `entries entry setValue is rejected and cannot mutate the source`() {
        val headers = Headers.builder().add("X-Foo", "1").build()
        val entry = headers.entries().single()
        assertFailsWith<UnsupportedOperationException> {
            (entry as MutableMap.MutableEntry<String, List<String>>).setValue(listOf("hacked"))
        }
        assertEquals(listOf("1"), headers.values("X-Foo"))
    }

    @Test
    fun `entries value list cannot be mutated through a MutableList cast`() {
        val headers = Headers.builder().add("X-Foo", "1").build()
        val values = headers.entries().single().value

        @Suppress("UNCHECKED_CAST")
        val asMutable = values as MutableList<String>
        assertFailsWith<UnsupportedOperationException> { asMutable.add("hacked") }
        // The source Headers is unchanged regardless of the cast.
        assertEquals(listOf("1"), headers.values("X-Foo"))
    }

    @Test
    fun `values returns an unmodifiable list that cannot mutate the source via cast`() {
        val headers =
            Headers.builder()
                .add("X-Foo", "1")
                .add("X-Foo", "2")
                .build()
        val values = headers.values("X-Foo")

        @Suppress("UNCHECKED_CAST")
        val asMutable = values as MutableList<String>
        assertFailsWith<UnsupportedOperationException> { asMutable.add("3") }
        assertFailsWith<UnsupportedOperationException> { asMutable.clear() }
        // A second read still observes the original two values — the source was not touched.
        assertEquals(listOf("1", "2"), headers.values("X-Foo"))
    }

    @Test
    fun `values typed overload returns an unmodifiable list`() {
        val headers = Headers.builder().add(HttpHeaderName.SET_COOKIE, "a=1").build()
        val values = headers.values(HttpHeaderName.SET_COOKIE)

        @Suppress("UNCHECKED_CAST")
        val asMutable = values as MutableList<String>
        assertFailsWith<UnsupportedOperationException> { asMutable.add("b=2") }
        assertEquals(listOf("a=1"), headers.values(HttpHeaderName.SET_COOKIE))
    }

    @Test
    fun `build snapshots values so later builder mutation cannot reach the built instance`() {
        val builder = Headers.builder().add("X-Foo", "1")
        val built = builder.build()
        // Mutate the same builder after build(); the built instance must not observe it.
        builder.add("X-Foo", "2")
        assertEquals(listOf("1"), built.values("X-Foo"))
    }

    @Test
    fun `values for absent header returns empty list (string overload)`() {
        val headers = Headers.builder().build()
        assertEquals(emptyList(), headers.values("X-Missing"))
    }

    @Test
    fun `values for absent typed header returns empty list`() {
        val headers = Headers.builder().build()
        assertEquals(emptyList(), headers.values(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `data class equality compares case-insensitively-normalised maps`() {
        val a = Headers.builder().set("Authorization", "Bearer abc").build()
        val b = Headers.builder().set("authorization", "Bearer abc").build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `unequal when values differ`() {
        val a = Headers.builder().set("Authorization", "Bearer a").build()
        val b = Headers.builder().set("Authorization", "Bearer b").build()
        assertFalse(a == b)
    }

    @Test
    fun `toString delegates to the underlying map`() {
        val headers = Headers.builder().set("X-Foo", "bar").build()
        // Just verify the toString contains the header's representation; we don't
        // pin the exact format so a downstream change to LinkedHashMap.toString is
        // not a behavioural regression.
        val s = headers.toString()
        assertTrue(s.contains("x-foo"))
        assertTrue(s.contains("bar"))
    }

    @Test
    fun `newBuilder round-trips and produces an equal Headers instance`() {
        val original =
            Headers.builder()
                .set("Authorization", "Bearer abc")
                .add("Set-Cookie", "a=1")
                .add("Set-Cookie", "b=2")
                .build()
        val rebuilt = original.newBuilder().build()
        assertEquals(original, rebuilt)
    }

    @Test
    fun `newBuilder mutations do not affect the source`() {
        val original = Headers.builder().set("X-Foo", "1").build()
        val mutated = original.newBuilder().set("X-Foo", "2").build()
        assertEquals("1", original.get("X-Foo"))
        assertEquals("2", mutated.get("X-Foo"))
    }

    @Test
    fun `companion builder() yields an empty Headers instance`() {
        val empty = Headers.builder().build()
        assertTrue(empty.names().isEmpty())
    }

    @Test
    fun `newBuilder clones entries`() {
        val seed = Headers.builder().set("Authorization", "Bearer abc").build()
        val derived = seed.newBuilder().set("Content-Type", "application/json").build()
        assertEquals("Bearer abc", derived.get("Authorization"))
        assertEquals("application/json", derived.get("Content-Type"))
        // Modifying the derived instance did not touch the seed.
        assertNull(seed.get("Content-Type"))
    }

    @Test
    fun `remove with string name is case-insensitive`() {
        val headers =
            Headers.builder()
                .set("Authorization", "Bearer abc")
                .remove("AUTHORIZATION")
                .build()
        assertFalse(headers.contains("authorization"))
    }
}
