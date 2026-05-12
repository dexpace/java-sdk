package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.instrumentation.NoopTracer
import org.dexpace.sdk.core.instrumentation.Tracer
import org.dexpace.sdk.core.instrumentation.UrlRedactor
import org.dexpace.sdk.core.instrumentation.metrics.Meter
import org.dexpace.sdk.core.instrumentation.metrics.NoopMeter

/**
 * Configuration for [InstrumentationStep].
 *
 * Default-allowed header names cover the safe HTTP semantics that don't leak credentials or
 * session state: `Content-Type`, `Content-Length`, `User-Agent`, etc. Headers outside this set
 * are emitted as `"REDACTED"` (when [isRedactedHeaderNamesLoggingEnabled] is `true`) or omitted
 * entirely (when `false`).
 *
 * Default-allowed query param names mirror [UrlRedactor.DEFAULT_ALLOWED] (i.e. `api-version`).
 *
 * ## WARNING — [HttpLogLevel.BODY_AND_HEADERS] and streaming
 *
 * When [logLevel] is [HttpLogLevel.BODY_AND_HEADERS] the response body is **fully drained
 * into memory** before the caller ever sees it (the drain happens eagerly inside the
 * instrumentation step). This is fundamentally incompatible with streaming responses:
 *
 *  - The entire response is buffered — large or unbounded payloads can exhaust the heap.
 *  - Backpressure is lost — the transport cannot pause the producer once the body is read.
 *  - First-byte latency for the caller approaches end-of-stream latency.
 *
 * Use [HttpLogLevel.HEADERS] (or [HttpLogLevel.NONE]) for endpoints that return large
 * downloads, server-sent events, gRPC, or chunked encodings whose size is unknown ahead
 * of time. [HttpLogLevel.BODY_AND_HEADERS] is intended for diagnostic builds against
 * small JSON/text payloads.
 */
class HttpInstrumentationOptions @JvmOverloads constructor(
    val logLevel: HttpLogLevel = HttpLogLevel.NONE,
    val allowedHeaderNames: Set<HttpHeaderName> = DEFAULT_ALLOWED_HEADERS,
    val allowedQueryParamNames: Set<String> = UrlRedactor.DEFAULT_ALLOWED,
    val isRedactedHeaderNamesLoggingEnabled: Boolean = true,
    val tracer: Tracer = NoopTracer,
    val meter: Meter = NoopMeter,
    val bodyPreviewMaxBytes: Int = DEFAULT_BODY_PREVIEW_MAX_BYTES,
) {
    companion object {
        // ~20 headers that are safe to surface by default — diagnostic, not credential.
        @JvmField
        val DEFAULT_ALLOWED_HEADERS: Set<HttpHeaderName> = setOf(
            HttpHeaderName.ACCEPT,
            HttpHeaderName.ACCEPT_ENCODING,
            HttpHeaderName.ACCEPT_LANGUAGE,
            HttpHeaderName.CACHE_CONTROL,
            HttpHeaderName.CONNECTION,
            HttpHeaderName.CONTENT_DISPOSITION,
            HttpHeaderName.CONTENT_ENCODING,
            HttpHeaderName.CONTENT_LENGTH,
            HttpHeaderName.CONTENT_RANGE,
            HttpHeaderName.CONTENT_TYPE,
            HttpHeaderName.DATE,
            HttpHeaderName.ETAG,
            HttpHeaderName.EXPIRES,
            HttpHeaderName.HOST,
            HttpHeaderName.LAST_MODIFIED,
            HttpHeaderName.LOCATION,
            HttpHeaderName.RANGE,
            HttpHeaderName.RETRY_AFTER,
            HttpHeaderName.TRANSFER_ENCODING,
            HttpHeaderName.USER_AGENT,
            HttpHeaderName.VIA,
            HttpHeaderName.X_FORWARDED_FOR,
            HttpHeaderName.X_REQUEST_ID,
        )

        const val DEFAULT_BODY_PREVIEW_MAX_BYTES: Int = 8 * 1024
    }
}
