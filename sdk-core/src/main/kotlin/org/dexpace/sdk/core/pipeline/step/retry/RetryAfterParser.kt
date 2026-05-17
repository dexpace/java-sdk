package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.http.common.Headers
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom

/**
 * Parses server-provided pacing hints from response headers and converts them into a
 * [Duration] suitable for delaying the next retry attempt.
 *
 * ## Precedence
 *
 * Mirrors Square's `RetryInterceptor` precedence:
 *
 *  1. `Retry-After: <seconds>` — RFC 7231 §7.1.3 numeric form.
 *  2. `Retry-After: <HTTP-date>` — RFC 7231 §7.1.3 absolute-date form.
 *  3. `X-RateLimit-Reset: <unix-epoch-seconds>` — de-facto rate-limit reset header used by
 *     GitHub, Stripe, Slack, Twilio, and others. Output is jittered to `[100%, 120%]` of
 *     the parsed delta so concurrent clients do not stampede the server at the exact
 *     reset moment.
 *
 * Returns `null` when none of the headers are parseable. Negative or malformed values are
 * treated as missing (return `null`) rather than as `Duration.ZERO`, so the retry
 * orchestrator can fall back to its exponential-backoff schedule rather than retrying
 * instantaneously against a server that asked us to slow down.
 *
 * ## Thread-safety
 *
 * Stateless — safe to invoke concurrently. [DateTimeFormatter.RFC_1123_DATE_TIME] is
 * immutable per its JDK contract.
 */
public object RetryAfterParser {
    /** Standard HTTP `Retry-After` header (RFC 7231 §7.1.3). */
    private const val HEADER_RETRY_AFTER = "Retry-After"

    /**
     * De-facto rate-limit reset header (`X-RateLimit-Reset`). Value is interpreted as a
     * Unix epoch in seconds — the same convention used by GitHub, Stripe, Slack, Twilio.
     */
    private const val HEADER_X_RATELIMIT_RESET = "X-RateLimit-Reset"

    /** Lower bound on the X-RateLimit-Reset jitter — keep waiting at least as long as the server asked. */
    private const val RATE_LIMIT_RESET_JITTER_MIN = 1.0

    /** Upper bound on the X-RateLimit-Reset jitter — at most 20% longer than the server's reset moment. */
    private const val RATE_LIMIT_RESET_JITTER_MAX = 1.2

    /**
     * Parses the next-attempt delay from [headers] relative to [now]. Returns `null` when no
     * recognized header is present or parseable.
     *
     * @param headers The response headers as received on the wire.
     * @param now The current instant — typically `Clock.systemUTC().instant()` in
     *   production code or a fixed value injected from tests. Used to compute the delta for
     *   the HTTP-date and Unix-epoch variants.
     * @return The parsed delay, or `null` if no header applies.
     */
    @JvmStatic
    public fun parse(
        headers: Headers,
        now: Instant,
    ): Duration? {
        val retryAfter = headers.get(HEADER_RETRY_AFTER)?.trim()
        if (!retryAfter.isNullOrEmpty()) {
            parseNumericSeconds(retryAfter)?.let { return it }
            parseHttpDate(retryAfter, now)?.let { return it }
        }
        val rateLimitReset = headers.get(HEADER_X_RATELIMIT_RESET)?.trim()
        if (!rateLimitReset.isNullOrEmpty()) {
            parseUnixEpochWithJitter(rateLimitReset, now)?.let { return it }
        }
        return null
    }

    /**
     * Parses [value] as a non-negative integer count of seconds. Returns `null` on any parse
     * failure, including negative values — the retry layer falls back to its backoff
     * schedule rather than retrying immediately against a misbehaving server.
     */
    private fun parseNumericSeconds(value: String): Duration? {
        val seconds = value.toLongOrNull() ?: return null
        if (seconds < 0L) return null
        return Duration.ofSeconds(seconds)
    }

    /**
     * Parses [value] as an RFC 7231 `HTTP-date`. Computes the duration between [now] and the
     * parsed timestamp; returns `Duration.ZERO` when the timestamp is in the past (server's
     * "retry-after" date has elapsed already), or `null` when the value does not parse.
     */
    private fun parseHttpDate(
        value: String,
        now: Instant,
    ): Duration? {
        val parsed: Instant =
            try {
                // RFC_1123_DATE_TIME requires a 4-digit year; the obsolete RFC 850 and ANSI
                // C asctime formats from RFC 7231 §7.1.1.1 are accepted by lenient parsing
                // but we keep to the SHOULD form. Servers in the wild stick to RFC 1123.
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: DateTimeParseException) {
                return null
            }
        return if (parsed.isBefore(now)) Duration.ZERO else Duration.between(now, parsed)
    }

    /**
     * Parses [value] as a Unix epoch in seconds (the X-RateLimit-Reset convention) and
     * returns a jittered delta in `[100%, 120%]` of `parsed − now`. The positive jitter
     * spreads concurrent clients past the exact reset moment so they don't stampede.
     *
     * Returns `null` on parse failure; returns `Duration.ZERO` when the reset moment has
     * already passed.
     */
    private fun parseUnixEpochWithJitter(
        value: String,
        now: Instant,
    ): Duration? {
        val seconds = value.toLongOrNull() ?: return null
        val target = Instant.ofEpochSecond(seconds)
        if (!target.isAfter(now)) return Duration.ZERO
        val delta = Duration.between(now, target)
        val factor = ThreadLocalRandom.current().nextDouble(RATE_LIMIT_RESET_JITTER_MIN, RATE_LIMIT_RESET_JITTER_MAX)
        val jitteredNanos = (delta.toNanos().toDouble() * factor).toLong()
        return Duration.ofNanos(jitteredNanos)
    }
}
