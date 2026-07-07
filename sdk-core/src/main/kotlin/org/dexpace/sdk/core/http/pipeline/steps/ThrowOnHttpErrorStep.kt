/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.throwOnError
import java.io.IOException

/**
 * Turns a non-successful HTTP response into the matching typed
 * [org.dexpace.sdk.core.http.response.exception.HttpException]. On a 4xx / 5xx response this step
 * buffers a bounded (≤ 1 MiB) copy of the error body — via [Response.throwOnError], which owns the
 * cap — and throws the mapped exception; on any other status (2xx, and any 1xx / 3xx that reaches
 * the step because no redirect was followed) it returns the response **untouched** — the body is
 * neither read, consumed, nor closed on that path.
 *
 * ## Stage placement — [Stage.PRE_REDIRECT] (outermost)
 *
 * This step must evaluate only the **final** response handed back to the caller — after redirect
 * following and after retry has exhausted. It therefore occupies [Stage.PRE_REDIRECT], the
 * outermost non-pillar stage, which runs *outside* both the redirect ([Stage.REDIRECT]) and retry
 * ([Stage.RETRY]) loops. Because a lower stage number wraps the higher ones, this step's
 * [process] sees the response **last**: it never observes an intermediate 3xx redirect hop (the
 * redirect step, running inside, has already followed it) and never preempts a retry of a
 * retryable 5xx (the retry step, running inside, has already exhausted its attempts). Placing the
 * step inside those loops would break both invariants — it would throw on the first 503 before the
 * retry step could recover it, and on a 3xx before the redirect step could follow it.
 *
 * ## Error-body buffering
 *
 * The heavy lifting is delegated to [Response.throwOnError], which drains up to 1 MiB of the error
 * body into an in-memory copy, releases the transport connection, and throws the mapped exception
 * carrying that bounded body — so the exception stays readable after the step returns while a
 * rogue multi-megabyte error payload cannot exhaust memory. The step only guards the call with the
 * response's own `status.isError` range check (4xx / 5xx) so a non-error status (which would trip
 * `throwOnError`'s / the factory's success-range check) is passed through untouched.
 *
 * ## Thread-safety
 *
 * Stateless after construction — safe to share across concurrent requests.
 */
public class ThrowOnHttpErrorStep : HttpStep {
    override val stage: Stage = Stage.PRE_REDIRECT

    @Throws(IOException::class)
    override fun process(
        request: Request,
        next: PipelineNext,
    ): Response {
        val response = next.process()
        // Success (and any non-error status) is returned verbatim: the body must not be read,
        // consumed, or closed on this path. Read the Status directly — no Int round-trip.
        if (!response.status.isError) return response
        // Error status: throwOnError buffers the bounded error body and throws the mapped
        // exception — it never returns here since the status is non-successful.
        return response.throwOnError()
    }
}
