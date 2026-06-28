/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryParamsTest {
    @Test
    fun `empty params have no entries`() {
        val params = QueryParams.empty()
        assertTrue(params.isEmpty())
        assertEquals(0, params.size())
        assertTrue(params.names().isEmpty())
        assertTrue(params.entries().isEmpty())
        assertEquals("", params.encode())
    }

    @Test
    fun `add accumulates multiple values for one name in insertion order`() {
        val params =
            QueryParams.builder()
                .add("tag", "a")
                .add("tag", "b")
                .add("tag", "c")
                .build()
        assertEquals(listOf("a", "b", "c"), params.values("tag"))
        assertEquals("a", params.get("tag"))
        assertEquals(3, params.size())
    }

    @Test
    fun `set replaces all existing values`() {
        val params =
            QueryParams.builder()
                .add("page", "1")
                .add("page", "2")
                .set("page", "9")
                .build()
        assertEquals(listOf("9"), params.values("page"))
    }

    @Test
    fun `set list replaces with multiple values`() {
        val params =
            QueryParams.builder()
                .add("k", "old")
                .set("k", listOf("x", "y"))
                .build()
        assertEquals(listOf("x", "y"), params.values("k"))
    }

    @Test
    fun `set null removes the parameter`() {
        val params =
            QueryParams.builder()
                .add("a", "1")
                .add("b", "2")
                .set("a", null)
                .build()
        assertFalse(params.contains("a"))
        assertTrue(params.contains("b"))
    }

    @Test
    fun `remove drops the name and is a no-op when absent`() {
        val params =
            QueryParams.builder()
                .add("a", "1")
                .remove("a")
                .remove("missing")
                .build()
        assertTrue(params.isEmpty())
    }

    @Test
    fun `add with null value stores empty string for a value-less param`() {
        val params = QueryParams.builder().add("flag", null as String?).build()
        assertTrue(params.contains("flag"))
        assertEquals("", params.get("flag"))
    }

    @Test
    fun `names are case-sensitive`() {
        val params =
            QueryParams.builder()
                .add("Page", "1")
                .add("page", "2")
                .build()
        assertEquals(listOf("1"), params.values("Page"))
        assertEquals(listOf("2"), params.values("page"))
        assertEquals(setOf("Page", "page"), params.names())
    }

    @Test
    fun `get returns null and values empty for absent name`() {
        val params = QueryParams.builder().add("a", "1").build()
        assertNull(params.get("b"))
        assertTrue(params.values("b").isEmpty())
        assertFalse(params.contains("b"))
    }

    @Test
    fun `encode percent-encodes names and values per rfc3986`() {
        val params =
            QueryParams.builder()
                .add("q", "a b&c")
                .add("name+key", "x")
                .build()
        // Space is %20 (not +); literal + is %2B; & is %26.
        assertEquals("q=a%20b%26c&name%2Bkey=x", params.encode())
    }

    @Test
    fun `encode renders a space as percent-20`() {
        val params = QueryParams.builder().add("q", "hello world").build()
        assertEquals("q=hello%20world", params.encode())
    }

    @Test
    fun `encode emits one pair per value for repeated names`() {
        val params =
            QueryParams.builder()
                .add("a", "1")
                .add("a", "2")
                .add("b", "3")
                .build()
        assertEquals("a=1&a=2&b=3", params.encode())
    }

    @Test
    fun `parse decodes names and values`() {
        val params = QueryParams.parse("q=a%20b%26c&page=2")
        assertEquals("a b&c", params.get("q"))
        assertEquals("2", params.get("page"))
    }

    @Test
    fun `parse reads a literal plus as a plus not a space`() {
        // RFC 3986: in a URL query, `+` is a literal `+`, unlike form-encoding.
        val params = QueryParams.parse("q=a+b")
        assertEquals("a+b", params.get("q"))
    }

    @Test
    fun `parse handles multi-value names`() {
        val params = QueryParams.parse("tag=a&tag=b&tag=c")
        assertEquals(listOf("a", "b", "c"), params.values("tag"))
    }

    @Test
    fun `parse treats a bare name as a value-less param`() {
        val params = QueryParams.parse("flag&other=1")
        assertTrue(params.contains("flag"))
        assertEquals("", params.get("flag"))
        assertEquals("1", params.get("other"))
    }

    @Test
    fun `parse treats name equals empty as empty value`() {
        val params = QueryParams.parse("flag=")
        assertEquals("", params.get("flag"))
    }

    @Test
    fun `parse tolerates a leading question mark`() {
        val params = QueryParams.parse("?a=1&b=2")
        assertEquals("1", params.get("a"))
        assertEquals("2", params.get("b"))
    }

    @Test
    fun `parse skips empty segments`() {
        val params = QueryParams.parse("a=1&&b=2&")
        assertEquals(listOf("a", "b"), params.names().toList())
    }

    @Test
    fun `parse of null or empty yields empty`() {
        assertTrue(QueryParams.parse(null).isEmpty())
        assertTrue(QueryParams.parse("").isEmpty())
        assertTrue(QueryParams.parse("?").isEmpty())
    }

    @Test
    fun `parse keeps raw text on malformed percent-encoding`() {
        // A stray '%' that is not a valid escape must not throw.
        val params = QueryParams.parse("bad=%zz")
        assertEquals("%zz", params.get("bad"))
    }

    @Test
    fun `encode then parse round-trips names values and order`() {
        val original =
            QueryParams.builder()
                .add("q", "hello world")
                .add("tag", "a")
                .add("tag", "b")
                .add("special", "100%")
                .add("plus", "a+b")
                .build()
        val roundTripped = QueryParams.parse(original.encode())
        assertEquals(original, roundTripped)
    }

    @Test
    fun `newBuilder is prefilled and does not mutate the source`() {
        val original = QueryParams.builder().add("a", "1").build()
        val derived = original.newBuilder().add("b", "2").build()
        assertFalse(original.contains("b"))
        assertEquals("1", derived.get("a"))
        assertEquals("2", derived.get("b"))
    }

    @Test
    fun `addAll merges and appends values for existing names`() {
        val base = QueryParams.builder().add("a", "1").add("b", "2").build()
        val extra = QueryParams.builder().add("a", "3").add("c", "4").build()
        val merged = base.newBuilder().addAll(extra).build()
        assertEquals(listOf("1", "3"), merged.values("a"))
        assertEquals(listOf("2"), merged.values("b"))
        assertEquals(listOf("4"), merged.values("c"))
    }

    @Test
    fun `values list is a defensive copy isolated from the source list`() {
        val source = mutableListOf("1")
        val params = QueryParams.builder().add("a", source).build()
        // Mutating the list passed into the builder must not reach the built instance.
        source.add("x")
        assertEquals(listOf("1"), params.values("a"))
    }

    @Test
    fun `entries snapshot is a defensive copy and reflects all names`() {
        val source = mutableListOf("1", "2")
        val params = QueryParams.builder().add("a", source).add("b", "3").build()
        val entries = params.entries()
        assertEquals(2, entries.size)
        // Mutating the source list afterwards must not change the exposed value list.
        source.add("x")
        val aValues = entries.first { it.key == "a" }.value
        assertEquals(listOf("1", "2"), aValues)
    }

    @Test
    fun `build is decoupled from the builder after the fact`() {
        val builder = QueryParams.builder().add("a", "1")
        val first = builder.build()
        builder.add("a", "2")
        assertEquals(listOf("1"), first.values("a"))
    }

    @Test
    fun `equality is by value and includes hashCode`() {
        val a = QueryParams.builder().add("x", "1").add("y", "2").build()
        val b = QueryParams.builder().add("x", "1").add("y", "2").build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equality is order-sensitive across names`() {
        val a = QueryParams.builder().add("x", "1").add("y", "2").build()
        val b = QueryParams.builder().add("y", "2").add("x", "1").build()
        assertNotEquals(a, b)
    }

    @Test
    fun `equality is order-sensitive across values of one name`() {
        val a = QueryParams.builder().add("t", "1").add("t", "2").build()
        val b = QueryParams.builder().add("t", "2").add("t", "1").build()
        assertNotEquals(a, b)
    }

    @Test
    fun `a name added with an empty value list yields params equal to empty`() {
        // A name with no values contributes nothing to encode(), so it must not leave a
        // phantom entry that breaks the "equal iff encode() identical" invariant.
        val params = QueryParams.builder().add("a", emptyList()).build()
        assertFalse(params.contains("a"))
        assertTrue(params.isEmpty())
        assertEquals("", params.encode())
        assertEquals(QueryParams.empty(), params)
        assertEquals(QueryParams.empty().hashCode(), params.hashCode())
    }

    @Test
    fun `set with an empty value list drops the name`() {
        val params = QueryParams.builder().add("a", "1").set("a", emptyList()).build()
        assertFalse(params.contains("a"))
        assertTrue(params.isEmpty())
    }

    @Test
    fun `a value-less param is retained and not confused with an empty value list`() {
        // add(name, null) stores a single empty-string value ("?flag"); that is NOT an empty
        // list and must survive build().
        val params = QueryParams.builder().add("flag", null as String?).build()
        assertTrue(params.contains("flag"))
        assertEquals("flag=", params.encode())
    }
}
