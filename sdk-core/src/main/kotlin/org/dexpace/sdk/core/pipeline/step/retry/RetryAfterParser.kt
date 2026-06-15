/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.util.DateTimeRfc1123
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom

/**
 * Canonical parser for server-provided pacing hints. Converts the recognized response-header
 * forms into a [Duration] suitable for delaying the next retry attempt.
 *
 * This is the **single source of truth** for `Retry-After`-style parsing across the SDK: both
 * the recovery-aware `RetryStep` and the stage-based `DefaultRetryStep` delegate here rather
 * than carrying their own inline parsers.
 *
 * ## Recognized header forms
 *
 *  1. `Retry-After: <seconds>` — RFC 7231 §7.1.3 numeric (delta-seconds) form. Both integer
 *     and fractional values (e.g. `1.5`) are accepted; the fractional part is honoured to
 *     nanosecond resolution.
 *  2. `Retry-After: <HTTP-date>` — RFC 7231 §7.1.3 absolute-date form (parsed via
 *     [DateTimeRfc1123], which tolerates an informational weekday per RFC 7231 §7.1.1.1).
 *  3. `retry-after-ms: <millis>` — millisecond delta variant.
 *  4. `x-ms-retry-after-ms: <millis>` — Microsoft/Azure millisecond delta variant.
 *  5. `X-RateLimit-Reset: <unix-epoch-seconds>` — de-facto rate-limit reset header used by
 *     GitHub, Stripe, Slack, Twilio, and others. Output is jittered to `[100%, 120%]` of the
 *     parsed delta so concurrent clients do not stampede the server at the exact reset moment.
 *
 * ## Precedence (the [parse] overload)
 *
 * When the whole [Headers] map is supplied, the standard `Retry-After` header wins over the
 * Microsoft millisecond variants, which in turn win over `X-RateLimit-Reset`. Within
 * `Retry-After`, the numeric form is tried before the HTTP-date form. Callers that need a
 * different, configurable precedence (e.g. [DefaultRetryStep] walking a caller-supplied header
 * list) drive the per-header dispatch directly via [parseHeaderValue].
 *
 * Returns `null` when none of the headers are parseable. Negative or malformed values are
 * treated as missing (return `null`) rather than as `Duration.ZERO`, so the retry orchestrator
 * can fall back to its exponential-backoff schedule rather than retrying instantaneously
 * against a server that asked us to slow down.
 *
 * ## Totality
 *
 * The parser is **total**: it never throws for any header value. Out-of-range or overflowing
 * inputs — an `X-RateLimit-Reset` of [Long.MAX_VALUE], an absurd far-future HTTP-date — map
 * to `null` (treated as "no usable hint"), and a far-future-but-valid delta is clamped to a
 * 365-day ceiling before any nanosecond conversion so [Duration.toNanos] can never overflow.
 * Every numeric/date conversion catches [DateTimeException] and [ArithmeticException] and
 * degrades to `null`.
 *
 * ## Thread-safety
 *
 * Stateless — safe to invoke concurrently. [DateTimeRfc1123] is immutable.
 */
public object RetryAfterParser {
    /** Standard HTTP `Retry-After` header (RFC 7231 §7.1.3). */
    private const val HEADER_RETRY_AFTER = "Retry-After"

    /** Millisecond delta variant of `Retry-After`. */
    private const val HEADER_RETRY_AFTER_MS = "retry-after-ms"

    /** Microsoft/Azure millisecond delta variant of `Retry-After`. */
    private const val HEADER_X_MS_RETRY_AFTER_MS = "x-ms-retry-after-ms"

    /**
     * De-facto rate-limit reset header (`X-RateLimit-Reset`). Value is interpreted as a
     * Unix epoch in seconds — the same convention used by GitHub, Stripe, Slack, Twilio.
     */
    private const val HEADER_X_RATELIMIT_RESET = "X-RateLimit-Reset"

    /**
     * Lower-cased interning key for `Retry-After`, used by [parseHeaderValue] to match against
     * [HttpHeaderName.caseInsensitiveName] (itself lower-cased per [HttpHeaderName.fromString]).
     */
    private const val KEY_RETRY_AFTER = "retry-after"

    /** Lower-cased interning key for `X-RateLimit-Reset` (see [KEY_RETRY_AFTER]). */
    private const val KEY_X_RATELIMIT_RESET = "x-ratelimit-reset"

    /** Lower bound on the X-RateLimit-Reset jitter — keep waiting at least as long as the server asked. */
    private const val RATE_LIMIT_RESET_JITTER_MIN = 1.0

    /** Upper bound on the X-RateLimit-Reset jitter — at most 20% longer than the server's reset moment. */
    private const val RATE_LIMIT_RESET_JITTER_MAX = 1.2

    /**
     * Ceiling applied to any computed delay before it is converted to nanoseconds.
     *
     * A server may legitimately emit a far-future `Retry-After` HTTP-date or a huge
     * `X-RateLimit-Reset` epoch; the resulting [Duration] can be large enough that
     * [Duration.toNanos] would overflow `Long` and throw [ArithmeticException]. Clamping to a
     * sane upper bound (365 days) keeps the parser total and yields a value the backoff
     * deadline cap can still reason about — no real pacing header asks a client to wait a
     * year, so clamping here loses nothing operationally.
     */
    private val MAX_DELAY: Duration = Duration.ofDays(365)

    /** [MAX_DELAY] expressed in whole seconds — the clamp threshold for fractional parsing. */
    private val MAX_DELAY_SECONDS: Double = MAX_DELAY.seconds.toDouble()

    /** Nanoseconds per second — the scale factor for the fractional `Retry-After` conversion. */
    private const val NANOS_PER_SECOND: Double = 1_000_000_000.0

    /**
     * Parses the next-attempt delay from [headers] relative to [now]. Returns `null` when no
     * recognized header is present or parseable.
     *
     * Precedence: `Retry-After` (numeric then HTTP-date) → `retry-after-ms` →
     * `x-ms-retry-after-ms` → `X-RateLimit-Reset`. The first form that yields a usable value
     * wins.
     *
     * @param headers The response headers as received on the wire.
     * @param now The current instant — typically `Clock.systemUTC().instant()` in
     *   production code or a fixed value injected from tests. Used to compute the delta for
     *   the HTTP-date and Unix-epoch variants.
     * @return The parsed delay, or `null` if no header applies.
     */
    @JvmStatic
    @Suppress("ReturnCount")
    public fun parse(
        headers: Headers,
        now: Instant,
    ): Duration? {
        val retryAfter = headers.get(HEADER_RETRY_AFTER)?.trim()
        if (!retryAfter.isNullOrEmpty()) {
            parseNumericSeconds(retryAfter)?.let { return it }
            parseHttpDate(retryAfter, now)?.let { return it }
        }
        headers.get(HEADER_RETRY_AFTER_MS)?.trim()?.let { value ->
            if (value.isNotEmpty()) parseMillis(value)?.let { return it }
        }
        headers.get(HEADER_X_MS_RETRY_AFTER_MS)?.trim()?.let { value ->
            if (value.isNotEmpty()) parseMillis(value)?.let { return it }
        }
        val rateLimitReset = headers.get(HEADER_X_RATELIMIT_RESET)?.trim()
        if (!rateLimitReset.isNullOrEmpty()) {
            parseUnixEpochWithJitter(rateLimitReset, now)?.let { return it }
        }
        return null
    }

    /**
     * Parses a single header value, dispatching on the header [name]'s known semantics:
     *
     *  - `Retry-After` → numeric seconds OR RFC 1123 HTTP-date.
     *  - `retry-after-ms` / `x-ms-retry-after-ms` → integer milliseconds.
     *  - `X-RateLimit-Reset` → Unix epoch seconds, jittered to `[100%, 120%]`.
     *  - any other name → treated as an integer-millisecond delta (the lenient default used by
     *    [DefaultRetryStep] for caller-supplied pacing headers).
     *
     * Used by [DefaultRetryStep] to honour its configurable, ordered
     * [HttpRetryOptions.retryAfterHeaders] list while keeping all parsing logic in one place.
     *
     * Total: returns `null` for any malformed, negative, or out-of-range value and never
     * throws.
     *
     * @param name The header the [value] was read from.
     * @param value The raw header value (leading/trailing whitespace is trimmed here).
     * @param now The current instant, used for the HTTP-date and Unix-epoch deltas.
     */
    @JvmStatic
    public fun parseHeaderValue(
        name: HttpHeaderName,
        value: String,
        now: Instant,
    ): Duration? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return when (name.caseInsensitiveName) {
            KEY_RETRY_AFTER ->
                parseNumericSeconds(trimmed) ?: parseHttpDate(trimmed, now)
            KEY_X_RATELIMIT_RESET ->
                parseUnixEpochWithJitter(trimmed, now)
            else ->
                // `retry-after-ms`, `x-ms-retry-after-ms`, and any other configured header are
                // interpreted as an integer-millisecond delta — the lenient default that the
                // stage-based retry step applies to caller-supplied pacing headers.
                parseMillis(trimmed)
        }
    }

    /**
     * Parses [value] as a non-negative count of seconds. Accepts both the RFC 7231 §7.1.3
     * integer (delta-seconds) form and the fractional form (`1.5`) that real servers and
     * proxies emit; the fractional part is honoured down to nanosecond resolution.
     *
     * Returns `null` on any parse failure, on a negative value, or on a non-finite value
     * (`NaN`, `Infinity`) — the retry layer then falls back to its backoff schedule rather
     * than retrying immediately against a misbehaving server. A finite but absurdly large
     * value is clamped to [MAX_DELAY] before the nanosecond conversion so the resulting
     * [Duration] can never overflow [Duration.toNanos] downstream.
     */
    private fun parseNumericSeconds(value: String): Duration? {
        val seconds = value.toDoubleOrNull() ?: return null
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return null
        // Clamp in the seconds domain before converting to nanos: a value beyond the ceiling
        // would overflow the `* NANOS_PER_SECOND` multiply and the Long cast below.
        if (seconds >= MAX_DELAY_SECONDS) return MAX_DELAY
        return Duration.ofNanos((seconds * NANOS_PER_SECOND).toLong())
    }

    /**
     * Parses [value] as a non-negative integer count of milliseconds. Returns `null` on any
     * parse failure, including negative values.
     */
    private fun parseMillis(value: String): Duration? {
        val millis = value.toLongOrNull() ?: return null
        if (millis < 0L) return null
        return Duration.ofMillis(millis)
    }

    /**
     * Parses [value] as an RFC 1123 `HTTP-date`. Computes the duration between [now] and the
     * parsed timestamp; returns `Duration.ZERO` when the timestamp is in the past (server's
     * "retry-after" date has elapsed already), or `null` when the value does not parse.
     *
     * Two grammars are accepted so the unified parser never regresses either subsystem it
     * replaced:
     *  1. [DateTimeRfc1123] — tolerant of an informational weekday (RFC 7231 §7.1.1.1), the
     *     `UTC` / `+0000` zone spellings, and case-insensitive month names, but requires the
     *     RFC-mandated two-digit day-of-month.
     *  2. [DateTimeFormatter.RFC_1123_DATE_TIME] — the JDK formatter, which additionally
     *     accepts a single-digit day-of-month (`1 Jun 2024`).
     *
     * Total by contract: a syntactically valid but absurdly far-future date (whose delta would
     * overflow [Duration] arithmetic) is clamped to [MAX_DELAY] rather than throwing.
     */
    private fun parseHttpDate(
        value: String,
        now: Instant,
    ): Duration? {
        val parsed = parseRfc1123Instant(value) ?: return null
        if (!parsed.isAfter(now)) return Duration.ZERO
        return safeDurationBetween(now, parsed)
    }

    /**
     * Resolves an RFC 1123 HTTP-date string to an [Instant], or `null` when neither accepted
     * grammar parses it. Never throws: [DateTimeException] from resolving an extreme year is
     * treated as malformed.
     */
    private fun parseRfc1123Instant(value: String): Instant? {
        try {
            return DateTimeRfc1123.parse(value)
        } catch (_: DateTimeParseException) {
            // Fall through to the JDK formatter, which accepts a single-digit day-of-month.
        } catch (_: DateTimeException) {
            return null
        }
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Parses [value] as a Unix epoch in seconds (the X-RateLimit-Reset convention) and
     * returns a jittered delta in `[100%, 120%]` of `parsed − now`. The positive jitter
     * spreads concurrent clients past the exact reset moment so they don't stampede.
     *
     * Returns `null` on parse failure; returns `Duration.ZERO` when the reset moment has
     * already passed.
     *
     * Total by contract: an out-of-range epoch (e.g. [Long.MAX_VALUE], for which
     * [Instant.ofEpochSecond] throws [DateTimeException]) yields `null`, and a far-future but
     * valid epoch is clamped to [MAX_DELAY] before the nanosecond conversion so neither
     * [Duration.toNanos] nor the jitter multiply can overflow.
     */
    private fun parseUnixEpochWithJitter(
        value: String,
        now: Instant,
    ): Duration? {
        val seconds = value.toLongOrNull() ?: return null
        val target =
            try {
                Instant.ofEpochSecond(seconds)
            } catch (_: DateTimeException) {
                return null
            }
        if (!target.isAfter(now)) return Duration.ZERO
        // Clamp before toNanos() so the delta and the jitter multiply both stay in range.
        val delta = safeDurationBetween(now, target)
        val factor = ThreadLocalRandom.current().nextDouble(RATE_LIMIT_RESET_JITTER_MIN, RATE_LIMIT_RESET_JITTER_MAX)
        val jitteredNanos = (delta.toNanos().toDouble() * factor).toLong().coerceAtLeast(0L)
        return Duration.ofNanos(jitteredNanos)
    }

    /**
     * Computes `target − now` as a [Duration], clamped to `[Duration.ZERO, MAX_DELAY]`.
     *
     * [Duration.between] never throws for valid [Instant]s, but the resulting duration can be
     * large enough that a later [Duration.toNanos] would overflow. Clamping to [MAX_DELAY]
     * here keeps every downstream `toNanos()` total. Callers guarantee `target` is after
     * `now`, so the result is never negative, but we coerce defensively all the same.
     */
    private fun safeDurationBetween(
        now: Instant,
        target: Instant,
    ): Duration {
        val between = Duration.between(now, target)
        return when {
            between.isNegative -> Duration.ZERO
            between > MAX_DELAY -> MAX_DELAY
            else -> between
        }
    }
}
