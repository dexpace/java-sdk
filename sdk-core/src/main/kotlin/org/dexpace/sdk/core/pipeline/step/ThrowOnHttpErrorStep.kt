/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory

/**
 * Response step that turns an HTTP error response into the matching typed [HttpException].
 *
 * This is the error path for the recovery-aware pipeline: a transport hands back a `Response`
 * for every completed exchange — including 4xx and 5xx — so a 4xx/5xx is a [Response], not a
 * thrown exception, until something maps it. Placed in a
 * [org.dexpace.sdk.core.pipeline.ResponsePipeline.responseSteps] list, this step performs that
 * mapping via [HttpExceptionFactory.fromResponse], so the typed [HttpException] family is
 * produced consistently from one seam rather than re-derived ad hoc at each call site.
 *
 * ## Behaviour
 *
 *  - **2xx (and 1xx/3xx) response** — returned unchanged; the success chain continues.
 *  - **4xx / 5xx response** — [HttpExceptionFactory.fromResponse] is invoked and the resulting
 *    [HttpException] is thrown. [org.dexpace.sdk.core.pipeline.ResponsePipeline] catches it,
 *    closes the in-hand response, and wraps it into a
 *    [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] — which then flows through the
 *    recovery chain (e.g. [org.dexpace.sdk.core.pipeline.step.retry.RetryStep]) exactly like a
 *    transport failure. Because the thrown [HttpException] implements
 *    [org.dexpace.sdk.core.http.response.exception.Retryable], retry classification keys off it
 *    uniformly.
 *
 * Range classification matches [HttpExceptionFactory]: only 400..599 maps to an exception, so
 * the success / informational / redirection ranges fall through untouched. Mapping the body
 * into a typed error value is left to the generated layer (see [HttpException.value]); this
 * step intentionally does not consume [Response.body].
 *
 * ## Thread-safety
 *
 * Stateless; safe to share across concurrent requests and reuse as a singleton.
 */
public object ThrowOnHttpErrorStep : ResponsePipelineStep {
    private const val ERROR_RANGE_MIN = 400
    private const val ERROR_RANGE_MAX = 599

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
        val code = input.status.code
        if (code in ERROR_RANGE_MIN..ERROR_RANGE_MAX) {
            throw HttpExceptionFactory.fromResponse(input)
        }
        return input
    }
}
