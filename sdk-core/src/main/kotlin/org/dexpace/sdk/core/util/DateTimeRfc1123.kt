package org.dexpace.sdk.core.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * RFC 1123 HTTP date-time parser and formatter.
 *
 * Used for `Date`, `Last-Modified`, `Expires`, and `Retry-After` HTTP headers. Emits the
 * canonical form `Sun, 06 Nov 1994 08:49:37 GMT`; parses the strict form plus the
 * obsolete RFC 850 / asctime variants and common zone spellings (`GMT`, `UTC`, `+0000`,
 * `+00:00`), with case-insensitive month and weekday names.
 */
public object DateTimeRfc1123 {

    /**
     * RFC 7231 §7.1.1.1 mandates a two-digit day-of-month (`06`, not `6`), whereas the
     * JDK's [DateTimeFormatter.RFC_1123_DATE_TIME] omits the leading zero on output. This
     * pattern matches the spec exactly.
     */
    private val EMITTER: DateTimeFormatter = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    /**
     * Case-insensitive wrapper around the JDK's RFC 1123 formatter. Even though the JDK's
     * own formatter tolerates lowercase month / weekday names in practice, this builder
     * makes the contract explicit and survives future JDK tightening.
     */
    private val TOLERANT_PARSER: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.RFC_1123_DATE_TIME)
        .toFormatter(Locale.US)

    /** Formats [instant] as an RFC 1123 string in UTC (`Thu, 01 Jan 2024 00:00:00 GMT`). */
    @JvmStatic
    public fun format(instant: Instant): String =
        EMITTER.format(instant.atOffset(ZoneOffset.UTC))

    /**
     * Parses an RFC 1123 string into an [Instant]. Tolerates obsolete RFC 850 / asctime
     * variants where reasonable, lowercase month / weekday names, and `GMT` / `UTC` /
     * `+0000` / `+00:00` zone spellings.
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
        return Instant.from(TOLERANT_PARSER.parse(normalized))
    }

    private fun normalizeZone(s: String): String {
        // Rewrite the trailing zone token to "GMT" when it is an aliased spelling for UTC.
        // We only touch the suffix to avoid mangling unrelated occurrences inside the string.
        // Aliases handled: "UTC" (case-insensitive), "+0000", and "+00:00" — all canonical
        // representations of the zero offset that the JDK RFC 1123 parser does not accept
        // verbatim.
        return when {
            s.endsWith(" UTC", ignoreCase = true) -> s.substring(0, s.length - 4) + " GMT"
            s.endsWith(" +0000") -> s.substring(0, s.length - 6) + " GMT"
            s.endsWith(" +00:00") -> s.substring(0, s.length - 7) + " GMT"
            else -> s
        }
    }
}
