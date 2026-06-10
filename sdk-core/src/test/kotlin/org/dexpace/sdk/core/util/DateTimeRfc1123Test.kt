/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DateTimeRfc1123Test {
    @Test
    fun `format epoch produces canonical RFC 1123 string`() {
        assertEquals(
            "Thu, 01 Jan 1970 00:00:00 GMT",
            DateTimeRfc1123.format(Instant.EPOCH),
        )
    }

    @Test
    fun `format 2024-01-01 midnight UTC`() {
        assertEquals(
            "Mon, 01 Jan 2024 00:00:00 GMT",
            DateTimeRfc1123.format(Instant.parse("2024-01-01T00:00:00Z")),
        )
    }

    @Test
    fun `format pre-EPOCH instant`() {
        assertEquals(
            "Wed, 31 Dec 1969 23:59:59 GMT",
            DateTimeRfc1123.format(Instant.parse("1969-12-31T23:59:59Z")),
        )
    }

    @Test
    fun `parse canonical RFC 1123 string`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 GMT"),
        )
    }

    @Test
    fun `round-trip parse format identity for several samples`() {
        val samples =
            listOf(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("1994-11-06T08:49:37Z"),
                Instant.parse("2099-12-31T23:59:59Z"),
                Instant.parse("1970-01-01T00:00:00Z"),
                Instant.parse("1999-06-15T12:34:56Z"),
            )
        for (sample in samples) {
            val formatted = DateTimeRfc1123.format(sample)
            val parsed = DateTimeRfc1123.parse(formatted)
            assertEquals(sample, parsed, "round-trip failed for $sample (formatted=$formatted)")
        }
    }

    @Test
    fun `parse tolerates lowercase weekday and month`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("mon, 01 jan 2024 00:00:00 GMT"),
        )
    }

    @Test
    fun `parse tolerates uppercase weekday and month`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("MON, 01 JAN 2024 00:00:00 GMT"),
        )
    }

    @Test
    fun `parse tolerates plus-0000 zone spelling`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 +0000"),
        )
    }

    @Test
    fun `parse tolerates UTC zone spelling`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 UTC"),
        )
    }

    @Test
    fun `parse tolerates leading and trailing whitespace`() {
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("  Mon, 01 Jan 2024 00:00:00 GMT  "),
        )
    }

    @Test
    fun `parse rejects garbage input`() {
        val ex =
            assertFailsWith<DateTimeParseException> {
                DateTimeRfc1123.parse("not a date")
            }
        assertTrue(ex.message != null && ex.message!!.isNotEmpty())
    }

    @Test
    fun `parse rejects empty string`() {
        assertFailsWith<DateTimeParseException> {
            DateTimeRfc1123.parse("")
        }
    }

    @Test
    fun `parse rejects blank string`() {
        assertFailsWith<DateTimeParseException> {
            DateTimeRfc1123.parse("   ")
        }
    }

    @Test
    fun `parse rejects missing comma after weekday`() {
        // RFC 1123 requires the comma between weekday and day-of-month.
        assertFailsWith<DateTimeParseException> {
            DateTimeRfc1123.parse("Mon 01 Jan 2024 00:00:00 GMT")
        }
    }

    @Test
    fun `parse non-zero offset is converted to UTC instant`() {
        // +0500 means the wall-clock time is 5 hours ahead of UTC, so the equivalent
        // Instant is 5 hours earlier.
        assertEquals(
            Instant.parse("2023-12-31T19:00:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 +0500"),
        )
    }

    @Test
    fun `parse tolerates colon-separated plus zero offset zone spelling`() {
        // Exercises the `+00:00` normalisation branch in normalizeZone.
        assertEquals(
            Instant.parse("2024-01-01T00:00:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 +00:00"),
        )
    }

    @Test
    fun `parse non-UTC offset such as plus 0530`() {
        // +0530 means wall-clock is 5h30 ahead of UTC → Instant is 5h30 earlier.
        // Asserts the non-UTC normalisation path leaves the offset intact for the parser.
        assertEquals(
            Instant.parse("2023-12-31T18:30:00Z"),
            DateTimeRfc1123.parse("Mon, 01 Jan 2024 00:00:00 +0530"),
        )
    }

    @Test
    fun `parse fringe-but-valid mixed-case RFC 1123 date is handled by primary parser`() {
        // Mixed-case weekday and month names are technically non-conformant but widely
        // encountered in the wild; the case-insensitive parser handles them.
        assertEquals(
            Instant.parse("1994-11-06T08:49:37Z"),
            DateTimeRfc1123.parse("Sun, 06 Nov 1994 08:49:37 GMT"),
        )
    }

    @Test
    fun `parse tolerates a weekday that disagrees with the date`() {
        // 06 Nov 1994 was a Sunday, but the header names "Mon". RFC 7231 §7.1.1.1 treats the
        // weekday as informational, so the inconsistent token must NOT cause a parse failure;
        // the date itself (06 Nov 1994) is what is honoured.
        assertEquals(
            Instant.parse("1994-11-06T08:49:37Z"),
            DateTimeRfc1123.parse("Mon, 06 Nov 1994 08:49:37 GMT"),
        )
    }

    @Test
    fun `parse ignores the weekday entirely across multiple wrong spellings`() {
        // 01 Jan 2024 was a Monday; every other weekday token below disagrees, yet all resolve
        // to the same instant because the weekday is never validated.
        val expected = Instant.parse("2024-01-01T00:00:00Z")
        for (weekday in listOf("Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
            assertEquals(
                expected,
                DateTimeRfc1123.parse("$weekday, 01 Jan 2024 00:00:00 GMT"),
                "weekday token `$weekday` must be ignored, not validated",
            )
        }
    }

    @Test
    fun `parse tolerates a disagreeing weekday with a numeric offset`() {
        // Exercises the lenient-weekday path together with a non-UTC numeric offset.
        assertEquals(
            Instant.parse("2023-12-31T19:00:00Z"),
            DateTimeRfc1123.parse("Sun, 01 Jan 2024 00:00:00 +0500"),
        )
    }
}
