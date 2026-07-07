/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.response.MAX_BUFFERED_ERROR_BODY_BYTES
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.bufferErrorBody
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory

/**
 * Response step that turns an HTTP error response into the matching typed [HttpException].
 *
 * This is the error path for the recovery-aware pipeline: a transport hands back a `Response`
 * for every completed exchange — including 4xx and 5xx — so a 4xx/5xx is a [Response], not a
 * thrown exception, until something maps it. Placed in a
 * [org.dexpace.sdk.core.pipeline.ResponseRecoveryChain.responseSteps] list, this step performs that
 * mapping via [HttpExceptionFactory.fromResponse], so the typed [HttpException] family is
 * produced consistently from one seam rather than re-derived ad hoc at each call site.
 *
 * ## Behaviour
 *
 *  - **2xx (and 1xx/3xx) response** — returned unchanged; the success chain continues.
 *  - **4xx / 5xx response** — [HttpExceptionFactory.fromResponse] is invoked and the resulting
 *    [HttpException] is thrown. [org.dexpace.sdk.core.pipeline.ResponseRecoveryChain] catches it,
 *    closes the in-hand response, and wraps it into a
 *    [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] — which then flows through the
 *    recovery chain (e.g. [org.dexpace.sdk.core.pipeline.step.retry.RetryRecovery]) exactly like a
 *    transport failure. Because the thrown [HttpException] implements
 *    [org.dexpace.sdk.core.http.response.exception.Retryable], retry classification keys off it
 *    uniformly.
 *
 * Range classification is single-sourced from [HttpExceptionFactory.isErrorStatus]: only
 * 400..599 maps to an exception, so the success / informational / redirection ranges fall
 * through untouched.
 *
 * ## Error-body buffering
 *
 * The pipeline closes the in-hand response as soon as this step throws (close-before-propagate),
 * which would also close the live response body. To keep the error body readable on the
 * resulting [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] — for a log-line
 * [HttpException.bodySnapshot] or downstream typed-error deserialization — this step buffers the
 * error body into a bounded, replayable in-memory body before constructing the exception, via the
 * shared [org.dexpace.sdk.core.http.response.bufferErrorBody] helper (the same routine that backs
 * [org.dexpace.sdk.core.http.response.throwOnError], so the two error-mapping paths share one
 * bounded, replayable implementation and cannot drift). The buffer is bounded at
 * [MAX_BUFFERED_ERROR_BODY_BYTES] — the same 1 MiB cap [org.dexpace.sdk.core.http.response.throwOnError]
 * and the stage-layer error mapping use, so all error-body buffering shares one bound and a
 * sub-megabyte error payload survives whole while a multi-megabyte 5xx body cannot OOM the process.
 * Mapping the buffered body into a typed error value is still left to the generated layer (see
 * [HttpException.value]).
 *
 * The cap is a hard truncation, not just an OOM guard: an error body larger than
 * [MAX_BUFFERED_ERROR_BODY_BYTES] is preserved only up to that many bytes, and the excess
 * is discarded with no marker. This is harmless for a log-line snapshot, but a downstream
 * consumer that deserializes the buffered body (e.g. the generated layer parsing it into
 * [HttpException.value]) must tolerate a structurally incomplete payload — a JSON object cut
 * mid-token will not parse. Callers that need the full error body must read it from the live
 * response before this step runs rather than relying on the buffered copy.
 *
 * ## Thread-safety
 *
 * Stateless; safe to share across concurrent requests and reuse as a singleton.
 */
public object ThrowOnHttpErrorRecovery : ResponsePipelineStep {
    /**
     * Returns [input] unchanged for a non-error status; throws the
     * [HttpExceptionFactory.fromResponse] mapping for a 4xx / 5xx status.
     *
     * @throws HttpException when `input.status.code` is in 400..599.
     */
    override fun execute(
        input: Response,
        context: DispatchContext,
    ): Response {
        if (!HttpExceptionFactory.isErrorStatus(input.status.code)) {
            return input
        }
        throw HttpExceptionFactory.fromResponse(
            bufferErrorBody(input, MAX_BUFFERED_ERROR_BODY_BYTES),
        )
    }
}
