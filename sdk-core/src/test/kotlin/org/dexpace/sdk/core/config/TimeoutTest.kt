/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeoutTest {
    // ----- Defaults / NONE -----

    @Test
    fun `defaults returns NONE with every phase zero`() {
        val t = Timeout.defaults()
        assertSame(Timeout.NONE, t)
        assertEquals(Duration.ZERO, t.connectTimeout)
        assertEquals(Duration.ZERO, t.readTimeout)
        assertEquals(Duration.ZERO, t.writeTimeout)
        assertEquals(Duration.ZERO, t.requestTimeout)
        assertEquals(Duration.ZERO, t.effectiveReadTimeout)
        assertEquals(Duration.ZERO, t.effectiveWriteTimeout)
    }

    @Test
    fun `empty builder equals NONE`() {
        assertEquals(Timeout.NONE, Timeout.builder().build())
    }

    // ----- Inheritance of read/write from request -----

    @Test
    fun `read and write inherit request when unset`() {
        val t = Timeout.builder().requestTimeout(Duration.ofSeconds(10)).build()
        assertEquals(Duration.ofSeconds(10), t.requestTimeout)
        // The raw stored values stay ZERO...
        assertEquals(Duration.ZERO, t.readTimeout)
        assertEquals(Duration.ZERO, t.writeTimeout)
        // ...but the effective values fall back to request.
        assertEquals(Duration.ofSeconds(10), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveWriteTimeout)
    }

    @Test
    fun `ofRequest sets request and inherits read write`() {
        val t = Timeout.ofRequest(Duration.ofSeconds(7))
        assertEquals(Duration.ofSeconds(7), t.requestTimeout)
        assertEquals(Duration.ofSeconds(7), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(7), t.effectiveWriteTimeout)
        assertEquals(Duration.ZERO, t.connectTimeout)
    }

    @Test
    fun `explicit read pins the read phase and does not inherit request`() {
        val t =
            Timeout
                .builder()
                .requestTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(3))
                .build()
        assertEquals(Duration.ofSeconds(3), t.effectiveReadTimeout)
        // Write still inherits request.
        assertEquals(Duration.ofSeconds(10), t.effectiveWriteTimeout)
    }

    @Test
    fun `explicit write pins the write phase independently`() {
        val t =
            Timeout
                .builder()
                .requestTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(4))
                .build()
        assertEquals(Duration.ofSeconds(4), t.effectiveWriteTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveReadTimeout)
    }

    @Test
    fun `explicit read of zero pins read to no-limit even when request is set`() {
        val t =
            Timeout
                .builder()
                .requestTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ZERO)
                .build()
        // Pinned to ZERO (no limit) — NOT inheriting the 10s request budget.
        assertEquals(Duration.ZERO, t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveWriteTimeout)
    }

    @Test
    fun `connect does not inherit request`() {
        val t = Timeout.builder().requestTimeout(Duration.ofSeconds(10)).build()
        assertEquals(Duration.ZERO, t.connectTimeout)
    }

    // ----- All phases set -----

    @Test
    fun `all phases set independently`() {
        val t =
            Timeout
                .builder()
                .connectTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ofSeconds(2))
                .writeTimeout(Duration.ofSeconds(3))
                .requestTimeout(Duration.ofSeconds(30))
                .build()
        assertEquals(Duration.ofSeconds(1), t.connectTimeout)
        assertEquals(Duration.ofSeconds(2), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(3), t.effectiveWriteTimeout)
        assertEquals(Duration.ofSeconds(30), t.requestTimeout)
    }

    // ----- Validation -----

    @Test
    fun `negative connect timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timeout.builder().connectTimeout(Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `negative read timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timeout.builder().readTimeout(Duration.ofMillis(-1))
        }
    }

    @Test
    fun `negative write timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timeout.builder().writeTimeout(Duration.ofNanos(-1))
        }
    }

    @Test
    fun `negative request timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timeout.builder().requestTimeout(Duration.ofSeconds(-5))
        }
    }

    @Test
    fun `unrepresentable duration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timeout.builder().requestTimeout(Duration.ofDays(365L * 1000))
        }
    }

    @Test
    fun `max nano-representable duration is accepted`() {
        val maxNanos = Duration.ofNanos(Long.MAX_VALUE)
        val t = Timeout.builder().requestTimeout(maxNanos).build()
        assertEquals(maxNanos, t.requestTimeout)
    }

    // ----- newBuilder round-trip -----

    @Test
    fun `newBuilder preserves all values including explicit flags`() {
        val original =
            Timeout
                .builder()
                .connectTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ZERO)
                .requestTimeout(Duration.ofSeconds(10))
                .build()
        val copy = original.newBuilder().build()
        assertEquals(original, copy)
        // read was explicitly pinned to ZERO; the copy must keep that, not re-inherit request.
        assertEquals(Duration.ZERO, copy.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(10), copy.effectiveWriteTimeout)
    }

    @Test
    fun `newBuilder allows overriding a single phase`() {
        val original = Timeout.ofRequest(Duration.ofSeconds(10))
        val modified = original.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        assertEquals(Duration.ofSeconds(2), modified.connectTimeout)
        assertEquals(Duration.ofSeconds(10), modified.requestTimeout)
        assertNotSame(original, modified)
    }

    // ----- equals / hashCode / toString -----

    @Test
    fun `equality compares effective phases so inherited and explicit equal forms match`() {
        val inherited = Timeout.builder().requestTimeout(Duration.ofSeconds(5)).build()
        val explicit =
            Timeout
                .builder()
                .requestTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .build()
        assertEquals(inherited, explicit)
        assertEquals(inherited.hashCode(), explicit.hashCode())
    }

    @Test
    fun `differing connect makes instances unequal`() {
        val a = Timeout.builder().connectTimeout(Duration.ofSeconds(1)).build()
        val b = Timeout.builder().connectTimeout(Duration.ofSeconds(2)).build()
        assertNotEquals(a, b)
    }

    @Test
    fun `equals handles identity and other types`() {
        val t = Timeout.ofRequest(Duration.ofSeconds(1))
        @Suppress("ReplaceCallWithBinaryOperator")
        assertTrue(t.equals(t))
        assertNotEquals<Any?>(t, "not a timeout")
        assertNotEquals<Any?>(t, null)
    }

    @Test
    fun `toString includes effective read and write`() {
        val t = Timeout.builder().requestTimeout(Duration.ofSeconds(9)).build()
        val s = t.toString()
        assertTrue(s.contains("request=PT9S"), s)
        assertTrue(s.contains("read=PT9S"), s)
        assertTrue(s.contains("write=PT9S"), s)
    }
}
