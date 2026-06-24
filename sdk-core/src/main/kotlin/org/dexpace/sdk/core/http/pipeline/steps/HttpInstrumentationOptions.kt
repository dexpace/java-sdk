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
 *    preview occupies the heap. Both steps **skip body capture for unknown-length (streaming)
 *    bodies** (`contentLength() < 0`): those stream to the caller unwrapped with no body preview.
 *    The bounded drain runs on whichever thread completes the call — the caller's thread in the
 *    sync step, the future-completion thread in the async step — and draining an unknown-length
 *    body could block that thread on a slow/idle producer (SSE, long-poll, chunked trickle),
 *    stalling time-to-first-byte. Known-length bodies keep the bounded preview.
 *  - **Request body**: the request-side tap is likewise capped at [bodyPreviewMaxBytes], so a
 *    large (e.g. multi-GB file) upload mirrors only a bounded preview into memory while the full
 *    payload streams zero-copy to the transport.
 *
 * Even with the cap, [HttpLogLevel.BODY_AND_HEADERS] wraps known-length response bodies in a
 * draining wrapper. Prefer [HttpLogLevel.HEADERS] (or [HttpLogLevel.NONE]) for endpoints that
 * return large downloads, server-sent events, gRPC, or chunked encodings whose size is unknown
 * ahead of time. [HttpLogLevel.BODY_AND_HEADERS] is intended for diagnostic builds against
 * small JSON/text payloads.
 *
 * Because the capture is a bounded preview, the logged `response.body.size` /
 * `response.body.preview` fields describe the **captured preview**, not necessarily the full
 * body: for a body larger than [bodyPreviewMaxBytes] the consumer still receives every byte
 * while those fields reflect only the preview prefix. To make the true size observable, the
 * event also carries `response.body.actual_size` (the full body length, emitted when known) and
 * `response.body.preview_truncated` (`true` when the preview is only a prefix). The request body
 * carries the matching `request.body.actual_size` / `request.body.preview_truncated` fields. The
 * separate `response.content.length` field still carries the body's true length when the origin
 * declared one. See `docs/http-body-logging-and-concurrency.md` ("Logged body size vs. the body
 * the consumer receives").
 *
 * @property bodyPreviewMaxBytes Upper bound, in bytes, on the in-memory body capture under
 *   [HttpLogLevel.BODY_AND_HEADERS]. Bounds the preview, not the body the consumer sees; the
 *   logged `*.body.size` fields are capped by it. Defaults to [DEFAULT_BODY_PREVIEW_MAX_BYTES].
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
