/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.http.common.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Unit tests for [RetryAfterParser]. Covers the three header forms (numeric Retry-After,
 * HTTP-date Retry-After, X-RateLimit-Reset Unix epoch), header precedence, missing-header
 * fallthrough, and malformed-value handling.
 */
class RetryAfterParserTest {
    private val now: Instant = Instant.parse("2024-06-01T12:00:00Z")

    private fun headers(vararg pairs: Pair<String, String>): Headers {
        val builder = Headers.Builder()
        for ((name, value) in pairs) builder.set(name, value)
        return builder.build()
    }

    // region -- numeric Retry-After --

    @Test
    fun `numeric Retry-After parses as seconds`() {
        val result = RetryAfterParser.parse(headers("Retry-After" to "5"), now)
        assertEquals(Duration.ofSeconds(5), result)
    }

    @Test
    fun `numeric Retry-After zero yields zero duration`() {
        assertEquals(Duration.ZERO, RetryAfterParser.parse(headers("Retry-After" to "0"), now))
    }

    @Test
    fun `numeric Retry-After negative returns null`() {
        assertNull(RetryAfterParser.parse(headers("Retry-After" to "-5"), now))
    }

    @Test
    fun `numeric Retry-After leading whitespace is trimmed and parsed`() {
        assertEquals(Duration.ofSeconds(7), RetryAfterParser.parse(headers("Retry-After" to "  7  "), now))
    }

    // endregion

    // region -- HTTP-date Retry-After --

    @Test
    fun `HTTP-date Retry-After yields duration relative to now`() {
        val target = now.plusSeconds(60)
        val httpDate = formatHttpDate(target)
        val result = RetryAfterParser.parse(headers("Retry-After" to httpDate), now)
        assertEquals(Duration.ofSeconds(60), result)
    }

    @Test
    fun `HTTP-date in the past yields zero duration`() {
        val target = now.minusSeconds(60)
        val httpDate = formatHttpDate(target)
        assertEquals(Duration.ZERO, RetryAfterParser.parse(headers("Retry-After" to httpDate), now))
    }

    @Test
    fun `HTTP-date malformed returns null`() {
        assertNull(RetryAfterParser.parse(headers("Retry-After" to "not-a-date"), now))
    }

    // endregion

    // region -- X-RateLimit-Reset --

    @Test
    fun `X-RateLimit-Reset Unix epoch jittered to between 100 and 120 percent`() {
        val resetAt = now.plusSeconds(10).epochSecond
        val baseHeaders = headers("X-RateLimit-Reset" to resetAt.toString())
        // Run multiple samples since the jitter is randomised; assert the bounds rather than
        // a single value. Ten samples is more than enough to fail loudly if the bounds are
        // wrong.
        repeat(JITTER_SAMPLES) {
            val result = RetryAfterParser.parse(baseHeaders, now)
            assertNotNull(result, "X-RateLimit-Reset should parse")
            val nanos = result!!.toNanos()
            val baseNanos = Duration.ofSeconds(10).toNanos()
            assertTrue(nanos >= baseNanos, "delay must be ≥ 100% of base (got $nanos vs $baseNanos)")
            assertTrue(nanos <= (baseNanos * JITTER_UPPER_BOUND).toLong(), "delay must be ≤ 120% of base (got $nanos)")
        }
    }

    @Test
    fun `X-RateLimit-Reset in the past yields zero duration`() {
        val resetAt = now.minusSeconds(5).epochSecond
        assertEquals(Duration.ZERO, RetryAfterParser.parse(headers("X-RateLimit-Reset" to resetAt.toString()), now))
    }

    @Test
    fun `X-RateLimit-Reset malformed returns null`() {
        assertNull(RetryAfterParser.parse(headers("X-RateLimit-Reset" to "not-an-epoch"), now))
    }

    // endregion

    // region -- header precedence --

    @Test
    fun `numeric Retry-After wins over X-RateLimit-Reset`() {
        val resetAt = now.plusSeconds(60).epochSecond
        val result =
            RetryAfterParser.parse(
                headers("Retry-After" to "3", "X-RateLimit-Reset" to resetAt.toString()),
                now,
            )
        assertEquals(Duration.ofSeconds(3), result)
    }

    @Test
    fun `HTTP-date Retry-After wins over X-RateLimit-Reset`() {
        val target = now.plusSeconds(15)
        val resetAt = now.plusSeconds(60).epochSecond
        val result =
            RetryAfterParser.parse(
                headers("Retry-After" to formatHttpDate(target), "X-RateLimit-Reset" to resetAt.toString()),
                now,
            )
        assertEquals(Duration.ofSeconds(15), result)
    }

    @Test
    fun `malformed Retry-After falls through to X-RateLimit-Reset`() {
        val resetAt = now.plusSeconds(10).epochSecond
        val result =
            RetryAfterParser.parse(
                headers("Retry-After" to "nonsense", "X-RateLimit-Reset" to resetAt.toString()),
                now,
            )
        assertNotNull(result, "Should fall through to X-RateLimit-Reset when Retry-After is malformed")
    }

    // endregion

    // region -- missing headers --

    @Test
    fun `no headers returns null`() {
        assertNull(RetryAfterParser.parse(Headers.builder().build(), now))
    }

    @Test
    fun `empty Retry-After header returns null`() {
        assertNull(RetryAfterParser.parse(headers("Retry-After" to ""), now))
    }

    @Test
    fun `whitespace-only Retry-After header returns null`() {
        assertNull(RetryAfterParser.parse(headers("Retry-After" to "   "), now))
    }

    // endregion

    private fun formatHttpDate(instant: Instant): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC))

    private companion object {
        private const val JITTER_SAMPLES = 10
        private const val JITTER_UPPER_BOUND = 1.2
    }
}
