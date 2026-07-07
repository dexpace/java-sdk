/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequestOptionsTest {
    @Test
    fun `EMPTY carries no overrides`() {
        val empty = RequestOptions.EMPTY
        assertNull(empty.timeout)
        assertNull(empty.maxRetries)
        assertTrue(empty.tags.isEmpty())
    }

    @Test
    fun `builder sets every field`() {
        val options =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .tag("route", "checkout")
                .tag("attempt", 3)
                .build()

        assertEquals(Duration.ofSeconds(5), options.timeout)
        assertEquals(0, options.maxRetries)
        assertEquals(mapOf<String, Any>("route" to "checkout", "attempt" to 3), options.tags)
    }

    @Test
    fun `timeout rejects a zero duration`() {
        assertFailsWith<IllegalArgumentException> {
            RequestOptions.builder().timeout(Duration.ZERO)
        }
    }

    @Test
    fun `timeout rejects a negative duration`() {
        assertFailsWith<IllegalArgumentException> {
            RequestOptions.builder().timeout(Duration.ofMillis(-1))
        }
    }

    @Test
    fun `maxRetries rejects a negative override`() {
        // Fail fast rather than let a negative value be silently reinterpreted downstream as
        // "use the configured default" — the opposite of a caller's likely intent to suppress
        // retries. Callers disable retries with 0, not a negative sentinel.
        assertFailsWith<IllegalArgumentException> {
            RequestOptions.builder().maxRetries(-1)
        }
    }

    @Test
    fun `maxRetries accepts zero to disable retries`() {
        assertEquals(0, RequestOptions.builder().maxRetries(0).build().maxRetries)
    }

    @Test
    fun `timeout accepts null to clear the override`() {
        val options = RequestOptions.builder().timeout(Duration.ofSeconds(1)).timeout(null).build()
        assertNull(options.timeout)
    }

    @Test
    fun `build with no tags shares the empty map`() {
        val a = RequestOptions.builder().maxRetries(2).build()
        val b = RequestOptions.builder().timeout(Duration.ofSeconds(1)).build()
        assertTrue(a.tags.isEmpty())
        assertSame(a.tags, b.tags)
    }

    @Test
    fun `tag insertion order is preserved`() {
        val options =
            RequestOptions.builder()
                .tag("a", 1)
                .tag("b", 2)
                .tag("c", 3)
                .build()

        assertEquals(listOf("a", "b", "c"), options.tags.keys.toList())
    }

    @Test
    fun `later tag with same key replaces earlier value`() {
        val options =
            RequestOptions.builder()
                .tag("k", "first")
                .tag("k", "second")
                .build()

        assertEquals("second", options.tags["k"])
    }

    @Test
    fun `tags snapshot is decoupled from the builder after build`() {
        val builder = RequestOptions.builder().tag("k", "v")
        val built = builder.build()
        // Mutating the builder after build must not leak into the already-built snapshot.
        builder.tag("k2", "v2")
        assertEquals(setOf("k"), built.tags.keys)
    }

    @Test
    fun `newBuilder round-trips all fields`() {
        val original =
            RequestOptions.builder()
                .timeout(Duration.ofMillis(750))
                .maxRetries(4)
                .tag("x", "y")
                .build()

        val copy = original.newBuilder().build()

        assertEquals(original, copy)
        assertNotSame(original, copy)
    }

    @Test
    fun `newBuilder allows overriding a single field`() {
        val original =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(1))
                .maxRetries(2)
                .build()

        val relaxed = original.newBuilder().maxRetries(null).build()

        assertEquals(Duration.ofSeconds(1), relaxed.timeout)
        assertNull(relaxed.maxRetries)
    }

    @Test
    fun `timeout null clears a previously-set timeout`() {
        val options =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(3))
                .timeout(null)
                .build()

        assertNull(options.timeout)
    }

    @Test
    fun `equals and hashCode are value-based`() {
        val a =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(2))
                .maxRetries(1)
                .tag("t", "v")
                .build()
        val b =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(2))
                .maxRetries(1)
                .tag("t", "v")
                .build()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differing fields are not equal`() {
        val base =
            RequestOptions.builder()
                .timeout(Duration.ofSeconds(2))
                .maxRetries(1)
                .build()

        assertFalse(base == base.newBuilder().timeout(Duration.ofSeconds(3)).build())
        assertFalse(base == base.newBuilder().maxRetries(2).build())
        assertFalse(base == base.newBuilder().tag("t", "v").build())
        assertEquals(RequestOptions.EMPTY, RequestOptions.builder().build())
    }
}
