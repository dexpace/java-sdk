/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.config.Configuration
import java.util.Locale

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
    ;

    public companion object {
        /**
         * Resolves a log level from the environment-variable [key], reading through the testable
         * env-source seam of [source].
         *
         * The SDK is a toolkit, not a product, so it deliberately bakes in **no** default key —
         * the caller (e.g. a generated client) supplies its own product's variable name, and
         * [source] supplies the lookup. Pass a [Configuration] built with
         * [org.dexpace.sdk.core.config.ConfigurationBuilder.envSource] to inject a hermetic env
         * in tests; in production a [Configuration] backed by `System.getenv` is the natural fit.
         *
         * Parsing is tolerant: the resolved value is matched against the enum names
         * case-insensitively and after trimming surrounding whitespace (so `headers`,
         * `HEADERS`, and `  Headers  ` all resolve to [HEADERS]). When the key is unset (or set
         * to an empty string, which [Configuration] treats as absent) or holds an unrecognized
         * value, [default] is returned. [default] itself defaults to [NONE] — logging stays off
         * unless explicitly opted into.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromEnv(
            key: String,
            source: Configuration,
            default: HttpLogLevel = NONE,
        ): HttpLogLevel {
            val raw = source.get(key) ?: return default
            return when (raw.trim().uppercase(Locale.US)) {
                "NONE" -> NONE
                "HEADERS" -> HEADERS
                "BODY_AND_HEADERS" -> BODY_AND_HEADERS
                else -> default
            }
        }
    }
}
