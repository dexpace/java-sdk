/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct tests for the internal [RequestRebuilder] URL helpers.
 *
 * Two properties matter here and are easy to regress:
 * 1. **New values use RFC 3986 encoding** — a space becomes `%20`, not `+`.
 * 2. **Untouched parameters are preserved byte-for-byte** — the rebuilder splices the raw
 *    query rather than decoding and re-encoding the whole thing, so a value-less flag stays
 *    value-less and reserved characters in other params are not rewritten.
 */
class RequestRebuilderTest {
    private fun url(spec: String): URL = URL(spec)

    @Test
    fun `setQueryParam appends a new param when absent`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items"), "page", "2")
        assertEquals("page=2", result.query)
    }

    @Test
    fun `setQueryParam replaces an existing param in place`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items?page=1&sort=asc"), "page", "2")
        assertEquals("page=2&sort=asc", result.query)
    }

    @Test
    fun `setQueryParam with null removes the param but keeps the others`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items?page=1&sort=asc"), "page", null)
        assertEquals("sort=asc", result.query)
    }

    @Test
    fun `setQueryParam dropping the only param yields no query`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items?page=1"), "page", null)
        assertNull(result.query)
    }

    @Test
    fun `setQueryParam encodes a space in the new value as percent-20`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items"), "q", "a b")
        assertEquals("q=a%20b", result.query)
    }

    @Test
    fun `setQueryParam encodes reserved characters in the new value`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items"), "token", "a+b/c=")
        assertEquals("token=a%2Bb%2Fc%3D", result.query)
    }

    @Test
    fun `setQueryParam preserves a value-less flag verbatim`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items?flag&page=1"), "page", "2")
        assertEquals("flag&page=2", result.query)
    }

    @Test
    fun `setQueryParam preserves reserved chars in untouched params verbatim`() {
        val result = RequestRebuilder.setQueryParam(url("https://api.example.com/items?filter=a:b&page=1"), "page", "2")
        assertEquals("filter=a:b&page=2", result.query)
    }

    @Test
    fun `getQueryParam returns the decoded value`() {
        assertEquals("2", RequestRebuilder.getQueryParam(url("https://api.example.com/items?page=2"), "page"))
    }

    @Test
    fun `getQueryParam returns null when absent`() {
        assertNull(RequestRebuilder.getQueryParam(url("https://api.example.com/items?page=2"), "cursor"))
    }

    @Test
    fun `getQueryParam returns null when there is no query`() {
        assertNull(RequestRebuilder.getQueryParam(url("https://api.example.com/items"), "page"))
    }

    @Test
    fun `getQueryParam decodes percent-escapes`() {
        assertEquals("a b", RequestRebuilder.getQueryParam(url("https://api.example.com/items?q=a%20b"), "q"))
    }

    @Test
    fun `getQueryParam reads a literal plus as a plus`() {
        assertEquals("a+b", RequestRebuilder.getQueryParam(url("https://api.example.com/items?q=a+b"), "q"))
    }

    @Test
    fun `getQueryParam reports an empty string for a value-less flag`() {
        assertEquals("", RequestRebuilder.getQueryParam(url("https://api.example.com/items?flag&page=2"), "flag"))
    }
}
