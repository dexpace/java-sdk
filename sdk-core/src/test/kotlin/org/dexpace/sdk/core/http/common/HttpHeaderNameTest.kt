/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpHeaderNameTest {
    @Test
    fun `fromString returns the same interned instance regardless of casing`() {
        val a = HttpHeaderName.fromString("Set-Cookie")
        val b = HttpHeaderName.fromString("set-cookie")
        val c = HttpHeaderName.fromString("SET-COOKIE")

        // All three look up the same canonical lower-case key, so they must be the
        // same instance (intern guarantee).
        assertSame(a, b, "lower-cased lookup should return the same instance")
        assertSame(a, c, "upper-cased lookup should return the same instance")
    }

    @Test
    fun `equals is case-insensitive and hashCode matches for equal instances`() {
        val a = HttpHeaderName.fromString("Content-Type")
        val b = HttpHeaderName.fromString("content-type")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "hashCode must agree for equal instances")
    }

    @Test
    fun `toString preserves the original case of the first caller`() {
        // Use a name unlikely to clash with anything previously interned by the
        // well-known constants block.
        val first = HttpHeaderName.fromString("X-Custom-FirstCase-Test")
        val second = HttpHeaderName.fromString("x-custom-firstcase-test")

        // The first interner wins: the case-preserving form is the one originally passed in.
        assertEquals("X-Custom-FirstCase-Test", first.toString())
        // And subsequent lookups (even with different casing) return the same instance,
        // so they observe the same case-preserving form.
        assertSame(first, second)
        assertEquals("X-Custom-FirstCase-Test", second.toString())
    }

    @Test
    fun `whitespace is trimmed before interning`() {
        val trimmed = HttpHeaderName.fromString("Authorization")
        val padded = HttpHeaderName.fromString("  Authorization  ")
        val tabbed = HttpHeaderName.fromString("\tAuthorization\n")

        assertSame(trimmed, padded, "leading/trailing spaces must be trimmed")
        assertSame(trimmed, tabbed, "other whitespace must be trimmed")
    }

    @Test
    fun `caseInsensitiveName is lower-cased and caseSensitiveName preserves input`() {
        val name = HttpHeaderName.fromString("X-Custom-Case-Preserve-A")
        assertEquals("x-custom-case-preserve-a", name.caseInsensitiveName)
        assertEquals("X-Custom-Case-Preserve-A", name.caseSensitiveName)
    }

    @Test
    fun `well-known constants are identity-equal to fromString lookups`() {
        assertSame(HttpHeaderName.AUTHORIZATION, HttpHeaderName.fromString("Authorization"))
        assertSame(HttpHeaderName.AUTHORIZATION, HttpHeaderName.fromString("authorization"))
        assertSame(HttpHeaderName.CONTENT_TYPE, HttpHeaderName.fromString("Content-Type"))
        assertSame(HttpHeaderName.SET_COOKIE, HttpHeaderName.fromString("set-cookie"))
        assertSame(HttpHeaderName.RETRY_AFTER_MS, HttpHeaderName.fromString("retry-after-ms"))
        assertSame(HttpHeaderName.X_MS_RETRY_AFTER_MS, HttpHeaderName.fromString("X-MS-Retry-After-Ms"))
    }

    @Test
    fun `fromString with empty string interns the empty key`() {
        val empty = HttpHeaderName.fromString("")
        val whitespace = HttpHeaderName.fromString("   ")
        // Both trim to the empty string and intern under the same key.
        assertSame(empty, whitespace)
        assertEquals("", empty.caseInsensitiveName)
        assertEquals("", empty.caseSensitiveName)
    }

    @Test
    fun `concurrent fromString calls converge on a single interned instance`() {
        // Pick a name not used by any well-known constant or other test so this race
        // is genuinely the first interning of the key.
        val name = "X-Concurrent-Intern-Race-Probe-${System.nanoTime()}"
        val threadCount = 8
        val results: AtomicReferenceArray<HttpHeaderName?> = AtomicReferenceArray(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        try {
            for (i in 0 until threadCount) {
                pool.submit {
                    try {
                        // All threads block on `start` so they hit `fromString` as
                        // close to simultaneously as the scheduler allows.
                        start.await()
                        results.set(i, HttpHeaderName.fromString(name))
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS), "threads did not finish in time")
        } finally {
            pool.shutdownNow()
        }

        val first = results.get(0)
        assertNotNull(first, "first slot should have been populated")
        for (i in 1 until threadCount) {
            assertSame(first, results.get(i), "slot $i diverged from the first interned instance")
        }
    }
}
