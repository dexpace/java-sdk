package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.DateTimeRfc1123
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Stamps the outgoing request with a `Date` header set to the current time in RFC 1123 format.
 *
 * Placed at [Stage.POST_RETRY] so each retry attempt receives a fresh timestamp; placing it
 * earlier (e.g. PRE_AUTH) would cache the time across attempts and risk false signatures on
 * services that bind the date into request signing.
 *
 * Thread-safety: stateless after construction — safe to share across concurrent requests.
 * Cancellation: blocking-free; no interrupt handling needed.
 */
class SetDateStep @JvmOverloads constructor(
    private val clock: Clock = Clock.SYSTEM,
) : HttpStep {
    override val stage: Stage = Stage.POST_RETRY

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        val stamped = request.newBuilder()
            .setHeader(HttpHeaderName.DATE.caseSensitiveName, formatNow())
            .build()
        return next.process(stamped)
    }

    /**
     * Formats the current instant as an RFC 1123 date-time string.
     *
     * Falls back to `DateTimeFormatter.RFC_1123_DATE_TIME` if the primary formatter throws
     * on a fringe `Instant`; the fallback path is intentionally silent (no log) to stay
     * allocation-light on the hot path.
     */
    private fun formatNow(): String {
        // Sample `clock.now()` exactly once so the primary and fallback formatters agree on
        // the instant even if the wall clock advances between calls.
        val now = clock.now()
        return try {
            DateTimeRfc1123.format(now)
        } catch (_: Throwable) {
            // Defensive: DateTimeRfc1123.format can throw for fringe Instant values that are
            // outside the representable RFC 1123 range (e.g. Instant.MIN / Instant.MAX). The
            // fallback to the JDK formatter is intentionally silent — no log, no rethrow — to
            // keep this step allocation-light on the hot path.
            FALLBACK_FORMATTER.format(now)
        }
    }

    private companion object {
        private val FALLBACK_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"))
    }
}
