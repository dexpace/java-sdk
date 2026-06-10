/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * RFC 1123 HTTP date-time parser and formatter.
 *
 * Used for `Date`, `Last-Modified`, `Expires`, and `Retry-After` HTTP headers. Emits the
 * canonical form `Sun, 06 Nov 1994 08:49:37 GMT`; parses the strict form plus common zone
 * spellings (`GMT`, `UTC`, `+0000`, `+00:00`), with case-insensitive month and weekday
 * names.
 *
 * Parsing treats the leading day-of-week token as informational only: RFC 7231 §7.1.1.1
 * says the weekday "is redundant with, and may be inconsistent with, the date," so a header
 * whose weekday disagrees with the actual date (`Mon, 06 Nov 1994 …` — 06 Nov 1994 is a
 * Sunday) is accepted rather than rejected. Robust HTTP clients ignore the weekday entirely.
 */
public object DateTimeRfc1123 {
    /**
     * RFC 7231 §7.1.1.1 mandates a two-digit day-of-month (`06`, not `6`), whereas the
     * JDK's [DateTimeFormatter.RFC_1123_DATE_TIME] omits the leading zero on output. This
     * pattern matches the spec exactly.
     */
    private val EMITTER: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    /**
     * Hand-built RFC 1123 parser without the leading weekday token. The day-of-week is
     * stripped from the input before parsing (see [stripWeekday]) so [ChronoField.DAY_OF_WEEK]
     * is never populated and a weekday inconsistent with the date cannot trigger a resolution
     * conflict (RFC 7231 §7.1.1.1 — the weekday is informational). Month spellings parse
     * case-insensitively; date, time, and zone follow the strict RFC 1123 grammar, accepting
     * the `GMT` zone name and numeric offsets such as `+0500`.
     */
    private val TOLERANT_PARSER: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral(' ')
            .appendPattern("MMM")
            .appendLiteral(' ')
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendLiteral(' ')
            .appendOffset("+HHMM", "GMT")
            .toFormatter(Locale.US)

    /** Formats [instant] as an RFC 1123 string in UTC (`Thu, 01 Jan 2024 00:00:00 GMT`). */
    @JvmStatic
    public fun format(instant: Instant): String = EMITTER.format(instant.atOffset(ZoneOffset.UTC))

    /**
     * Parses an RFC 1123 string into an [Instant]. Tolerates case-insensitive month names,
     * `GMT` / `UTC` / `+0000` / `+00:00` zone spellings, and a leading weekday token that is
     * inconsistent with the date (the weekday is informational per RFC 7231 §7.1.1.1 and is
     * not validated). The day-of-month-and-onwards grammar is otherwise strict.
     *
     * @throws DateTimeParseException on malformed or blank input.
     */
    @JvmStatic
    @Throws(DateTimeParseException::class)
    public fun parse(raw: String): Instant {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw DateTimeParseException("RFC 1123 date-time string is blank", raw, 0)
        }
        // The JDK formatter does not recognise "UTC" as a zone name (only "GMT" and
        // numeric offsets). Normalize the trailing token so aliased spellings parse.
        val normalized = normalizeZone(trimmed)
        // Drop the informational weekday prefix so a weekday inconsistent with the date does
        // not surface a resolution conflict; the day-of-week is never validated.
        val withoutWeekday = stripWeekday(normalized)
        return Instant.from(TOLERANT_PARSER.parse(withoutWeekday))
    }

    /**
     * Removes a leading `Xxx, ` weekday token (three letters, a comma, and a space) when
     * present. A missing comma is left untouched so the malformed `Mon 01 Jan …` form still
     * fails to parse, preserving the strict-comma contract.
     */
    private fun stripWeekday(s: String): String {
        val comma = s.indexOf(',')
        if (comma in 1 until WEEKDAY_TOKEN_LEN) {
            // `Xxx, ` — skip the comma and the single following space.
            var i = comma + 1
            if (i < s.length && s[i] == ' ') i++
            return s.substring(i)
        }
        return s
    }

    private fun normalizeZone(s: String): String {
        // Rewrite the trailing zone token to "GMT" when it is an aliased spelling for UTC.
        // We only touch the suffix to avoid mangling unrelated occurrences inside the string.
        // Aliases handled: "UTC" (case-insensitive), "+0000", and "+00:00" — all canonical
        // representations of the zero offset that the JDK RFC 1123 parser does not accept
        // verbatim.
        return when {
            s.endsWith(" UTC", ignoreCase = true) -> s.substring(0, s.length - ZONE_UTC_LEN) + " GMT"
            s.endsWith(" +0000") -> s.substring(0, s.length - ZONE_NUMERIC_LEN) + " GMT"
            s.endsWith(" +00:00") -> s.substring(0, s.length - ZONE_NUMERIC_COLON_LEN) + " GMT"
            else -> s
        }
    }

    // String-length offsets corresponding to the literal zone-suffix tokens recognised by
    // [normalizeZone]: " UTC" (4 chars), " +0000" (6 chars), " +00:00" (7 chars).
    private const val ZONE_UTC_LEN = 4
    private const val ZONE_NUMERIC_LEN = 6
    private const val ZONE_NUMERIC_COLON_LEN = 7

    // RFC 1123 mandates the three-letter weekday abbreviation, so the separating comma in a
    // well-formed header sits at index 3 (`Mon,`). [stripWeekday] only removes the prefix when
    // the comma falls within `1 until WEEKDAY_TOKEN_LEN`, i.e. at index 1, 2, or 3.
    private const val WEEKDAY_TOKEN_LEN = 4
}
