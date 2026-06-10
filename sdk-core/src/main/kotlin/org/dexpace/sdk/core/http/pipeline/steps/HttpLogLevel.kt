/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

/**
 * Granularity of HTTP logging emitted by [InstrumentationStep].
 *
 * The chosen level applies uniformly to every request the step observes — finer control (e.g.
 * disabling body logging for one operation) is the calling code's responsibility, not the step's.
 */
public enum class HttpLogLevel {
    /** No request/response events emitted. Spans still start/end if a [org.dexpace.sdk.core.instrumentation.Tracer] is wired up. */
    NONE,

    /** Method, URL (redacted), status, headers (allow-listed) — no body capture. */
    HEADERS,

    /**
     * Headers plus the request/response body. The request body is captured during write
     * via [org.dexpace.sdk.core.http.request.LoggableRequestBody]; the response body is
     * captured into [org.dexpace.sdk.core.http.response.LoggableResponseBody].
     *
     * Capture is **bounded** to the configured preview size (`bodyPreviewMaxBytes`), so large
     * or streaming responses are not buffered whole — the caller still streams the remainder.
     * See [HttpInstrumentationOptions] for the streaming and async-completion-thread caveats.
     */
    BODY_AND_HEADERS,
}
