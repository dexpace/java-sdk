/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
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
 * Unit tests for [RetryAfterParser]. Covers all recognized header forms (numeric Retry-After,
 * HTTP-date Retry-After, `retry-after-ms`, `x-ms-retry-after-ms`, and X-RateLimit-Reset Unix
 * epoch), header precedence, the per-header [RetryAfterParser.parseHeaderValue] dispatch,
 * missing-header fallthrough, and malformed/out-of-range handling.
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

    // region -- millisecond Retry-After variants --

    @Test
    fun `retry-after-ms parses as milliseconds`() {
        assertEquals(Duration.ofMillis(1500), RetryAfterParser.parse(headers("retry-after-ms" to "1500"), now))
    }

    @Test
    fun `x-ms-retry-after-ms parses as milliseconds`() {
        assertEquals(Duration.ofMillis(2500), RetryAfterParser.parse(headers("x-ms-retry-after-ms" to "2500"), now))
    }

    @Test
    fun `retry-after-ms zero yields zero duration`() {
        assertEquals(Duration.ZERO, RetryAfterParser.parse(headers("retry-after-ms" to "0"), now))
    }

    @Test
    fun `retry-after-ms negative returns null`() {
        assertNull(RetryAfterParser.parse(headers("retry-after-ms" to "-100"), now))
    }

    @Test
    fun `retry-after-ms malformed returns null`() {
        assertNull(RetryAfterParser.parse(headers("retry-after-ms" to "soon"), now))
    }

    @Test
    fun `all four header forms each parse on their own`() {
        // Each recognized form, in isolation, yields a usable hint via the whole-headers parse.
        assertEquals(Duration.ofSeconds(5), RetryAfterParser.parse(headers("Retry-After" to "5"), now))
        assertEquals(Duration.ofMillis(750), RetryAfterParser.parse(headers("retry-after-ms" to "750"), now))
        assertEquals(Duration.ofMillis(900), RetryAfterParser.parse(headers("x-ms-retry-after-ms" to "900"), now))
        val resetAt = now.plusSeconds(10).epochSecond
        assertNotNull(
            RetryAfterParser.parse(headers("X-RateLimit-Reset" to resetAt.toString()), now),
            "X-RateLimit-Reset must parse on its own",
        )
    }

    // endregion

    // region -- parseHeaderValue dispatch --

    @Test
    fun `parseHeaderValue dispatches Retry-After to seconds`() {
        assertEquals(
            Duration.ofSeconds(8),
            RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER, "8", now),
        )
    }

    @Test
    fun `parseHeaderValue dispatches Retry-After to HTTP-date`() {
        val httpDate = formatHttpDate(now.plusSeconds(30))
        assertEquals(
            Duration.ofSeconds(30),
            RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER, httpDate, now),
        )
    }

    @Test
    fun `parseHeaderValue dispatches retry-after-ms to milliseconds`() {
        assertEquals(
            Duration.ofMillis(1234),
            RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER_MS, "1234", now),
        )
    }

    @Test
    fun `parseHeaderValue dispatches x-ms-retry-after-ms to milliseconds`() {
        assertEquals(
            Duration.ofMillis(4321),
            RetryAfterParser.parseHeaderValue(HttpHeaderName.X_MS_RETRY_AFTER_MS, "4321", now),
        )
    }

    @Test
    fun `parseHeaderValue dispatches X-RateLimit-Reset to jittered epoch`() {
        val resetAt = now.plusSeconds(10).epochSecond
        val name = HttpHeaderName.fromString("X-RateLimit-Reset")
        repeat(JITTER_SAMPLES) {
            val result = RetryAfterParser.parseHeaderValue(name, resetAt.toString(), now)
            assertNotNull(result, "X-RateLimit-Reset must parse via parseHeaderValue")
            val nanos = result!!.toNanos()
            val baseNanos = Duration.ofSeconds(10).toNanos()
            assertTrue(nanos >= baseNanos, "delay must be ≥ 100% of base (got $nanos)")
            assertTrue(nanos <= (baseNanos * JITTER_UPPER_BOUND).toLong(), "delay must be ≤ 120% of base (got $nanos)")
        }
    }

    @Test
    fun `parseHeaderValue routes an unknown header name to the millisecond fallback`() {
        val custom = HttpHeaderName.fromString("X-Custom-Retry-After-Ms")
        assertEquals(Duration.ofMillis(640), RetryAfterParser.parseHeaderValue(custom, "640", now))
    }

    @Test
    fun `parseHeaderValue trims surrounding whitespace`() {
        assertEquals(
            Duration.ofSeconds(9),
            RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER, "  9  ", now),
        )
    }

    @Test
    fun `parseHeaderValue returns null for an empty or whitespace-only value`() {
        assertNull(RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER, "", now))
        assertNull(RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER_MS, "   ", now))
    }

    @Test
    fun `parseHeaderValue is total for malformed and oversized values`() {
        assertNull(RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER, "not-a-number", now))
        assertNull(RetryAfterParser.parseHeaderValue(HttpHeaderName.RETRY_AFTER_MS, "-5", now))
        assertNull(
            RetryAfterParser.parseHeaderValue(
                HttpHeaderName.fromString("X-RateLimit-Reset"),
                Long.MAX_VALUE.toString(),
                now,
            ),
            "Out-of-range epoch must yield null, not throw",
        )
    }

    // endregion

    // region -- totality / out-of-range inputs (P-1) --

    @Test
    fun `X-RateLimit-Reset of Long MAX_VALUE returns null without throwing`() {
        // Instant.ofEpochSecond(Long.MAX_VALUE) throws DateTimeException; the parser must
        // catch it and report "no usable hint" rather than propagating.
        val result = RetryAfterParser.parse(headers("X-RateLimit-Reset" to Long.MAX_VALUE.toString()), now)
        assertNull(result)
    }

    @Test
    fun `X-RateLimit-Reset far-future epoch is clamped instead of overflowing`() {
        // A valid-but-huge epoch: Duration.between(now, target).toNanos() would overflow Long.
        // The parser clamps to the 365-day ceiling, so the result is non-null and bounded.
        val farFuture = 99_999_999_999L
        val result = RetryAfterParser.parse(headers("X-RateLimit-Reset" to farFuture.toString()), now)
        assertNotNull(result, "Far-future epoch must clamp, not throw or return null")
        // Clamp ceiling is 365 days; jitter can push up to 120%, so allow headroom.
        val maxClampNanos = Duration.ofDays(MAX_CLAMP_DAYS).toNanos()
        assertTrue(
            result!!.toNanos() <= (maxClampNanos.toDouble() * JITTER_UPPER_BOUND).toLong(),
            "Clamped delay must stay within the 365-day ceiling plus jitter (got ${result.toNanos()})",
        )
    }

    @Test
    fun `far-future HTTP-date Retry-After is clamped instead of overflowing`() {
        // Year 9999 is a valid RFC 1123 date but its delta from 2024 overflows Duration nanos.
        val farDate = "Fri, 31 Dec 9999 23:59:59 GMT"
        val result = RetryAfterParser.parse(headers("Retry-After" to farDate), now)
        assertNotNull(result, "Far-future HTTP-date must clamp, not throw or return null")
        assertEquals(Duration.ofDays(MAX_CLAMP_DAYS), result, "Far-future HTTP-date must clamp to the 365-day ceiling")
    }

    @Test
    fun `numeric Retry-After of Long MAX_VALUE does not throw`() {
        // Duration.ofSeconds(Long.MAX_VALUE) is representable; the downstream consumer clamps.
        val result = RetryAfterParser.parse(headers("Retry-After" to Long.MAX_VALUE.toString()), now)
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), result)
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

    @Test
    fun `Retry-After wins over retry-after-ms`() {
        val result =
            RetryAfterParser.parse(headers("Retry-After" to "4", "retry-after-ms" to "9999"), now)
        assertEquals(Duration.ofSeconds(4), result)
    }

    @Test
    fun `retry-after-ms wins over X-RateLimit-Reset`() {
        val resetAt = now.plusSeconds(60).epochSecond
        val result =
            RetryAfterParser.parse(
                headers("retry-after-ms" to "2000", "X-RateLimit-Reset" to resetAt.toString()),
                now,
            )
        assertEquals(Duration.ofMillis(2000), result)
    }

    @Test
    fun `malformed retry-after-ms falls through to x-ms-retry-after-ms`() {
        val result =
            RetryAfterParser.parse(
                headers("retry-after-ms" to "bad", "x-ms-retry-after-ms" to "1750"),
                now,
            )
        assertEquals(Duration.ofMillis(1750), result)
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
        private const val MAX_CLAMP_DAYS = 365L
    }
}
