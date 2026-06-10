/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
 * When [logLevel] is [HttpLogLevel.BODY_AND_HEADERS] both the request and response bodies are
 * captured for logging, but the capture is **bounded to [bodyPreviewMaxBytes]** — only a preview
 * prefix is held in memory, not the whole body:
 *
 *  - **Response body**: at most [bodyPreviewMaxBytes] bytes are buffered. A body within the cap
 *    is fully captured and stays repeatable; a larger body still streams in full to the caller
 *    (the wrapper replays the captured prefix then continues from the live tail) while only the
 *    preview occupies the heap. In the **sync** step the bounded drain happens eagerly inside
 *    the step. In the **async** step the bounded drain runs on the future-completion thread, so
 *    it is **skipped for unknown-length (streaming) bodies** (`contentLength() < 0`) — those
 *    stream to the caller unwrapped with no body preview, so a slow/idle producer never blocks
 *    the completion thread.
 *  - **Request body**: the request-side tap is likewise capped at [bodyPreviewMaxBytes], so a
 *    large (e.g. multi-GB file) upload mirrors only a bounded preview into memory while the full
 *    payload streams zero-copy to the transport.
 *
 * Even with the cap, [HttpLogLevel.BODY_AND_HEADERS] wraps known-length response bodies in a
 * draining wrapper. Prefer [HttpLogLevel.HEADERS] (or [HttpLogLevel.NONE]) for endpoints that
 * return large downloads, server-sent events, gRPC, or chunked encodings whose size is unknown
 * ahead of time. [HttpLogLevel.BODY_AND_HEADERS] is intended for diagnostic builds against
 * small JSON/text payloads.
 */
public class HttpInstrumentationOptions
    @JvmOverloads
    constructor(
        public val logLevel: HttpLogLevel = HttpLogLevel.NONE,
        public val allowedHeaderNames: Set<HttpHeaderName> = DEFAULT_ALLOWED_HEADERS,
        public val allowedQueryParamNames: Set<String> = UrlRedactor.DEFAULT_ALLOWED,
        public val isRedactedHeaderNamesLoggingEnabled: Boolean = true,
        public val tracer: Tracer = NoopTracer,
        public val meter: Meter = NoopMeter,
        public val bodyPreviewMaxBytes: Int = DEFAULT_BODY_PREVIEW_MAX_BYTES,
    ) {
        public companion object {
            // ~20 headers that are safe to surface by default — diagnostic, not credential.
            @JvmField
            public val DEFAULT_ALLOWED_HEADERS: Set<HttpHeaderName> =
                setOf(
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

            public const val DEFAULT_BODY_PREVIEW_MAX_BYTES: Int = 8 * 1024
        }
    }
