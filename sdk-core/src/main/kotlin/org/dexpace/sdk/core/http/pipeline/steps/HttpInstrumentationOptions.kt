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
