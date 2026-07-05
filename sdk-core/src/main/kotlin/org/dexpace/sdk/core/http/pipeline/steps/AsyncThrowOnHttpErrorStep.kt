/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.throwOnError
import java.util.concurrent.CompletableFuture

/**
 * Async mirror of [ThrowOnHttpErrorStep]. Maps a 4xx / 5xx completion into a typed
 * [org.dexpace.sdk.core.http.response.exception.HttpException] on the [CompletableFuture] path,
 * buffering a bounded (≤ 1 MiB) error body via [Response.throwOnError]; a 2xx (or any non-error
 * status) completes with the response untouched.
 *
 * The mapping runs in the future's success continuation ([CompletableFuture.thenApply]), so an
 * upstream failure propagates unchanged and the thrown exception completes the returned future
 * exceptionally — wrapped in a [java.util.concurrent.CompletionException] per `CompletableFuture`
 * semantics, with the mapped exception as its cause. Callers using `join()` / `get()` observe the
 * mapped exception through that wrapper (or directly via
 * [org.dexpace.sdk.core.util.Futures.unwrap]).
 *
 * Placement is identical to the synchronous step — [Stage.PRE_REDIRECT], outermost — so it only
 * evaluates the terminal response after async redirect/retry have run. See [ThrowOnHttpErrorStep]
 * for the full stage-placement and buffering rationale.
 *
 * ## Error-body buffering runs on the completing thread
 *
 * The [CompletableFuture.thenApply] mapping runs **synchronously on whatever thread completed the
 * upstream future** — typically the transport's I/O / completion thread. On a 4xx / 5xx it calls
 * [Response.throwOnError], which **blocks** while it drains up to 1 MiB of the error body from the
 * network source into the in-memory copy. The success path never reads a byte, so this cost is
 * paid only for error responses. Callers that supply a bounded completion executor to a
 * bring-your-own async transport (e.g. `java.net.http.HttpClient`) should size that executor with
 * this drain in mind: a burst of large error responses can hold completion threads for the
 * duration of the buffering, delaying unrelated exchanges that share the pool. There is no
 * executor hop here — doing so would need machinery the async pipeline does not yet expose.
 *
 * ## Thread-safety
 *
 * Stateless after construction — safe to share across concurrent requests.
 */
public class AsyncThrowOnHttpErrorStep : AsyncHttpStep {
    override val stage: Stage = Stage.PRE_REDIRECT

    override fun processAsync(
        request: Request,
        next: AsyncPipelineNext,
    ): CompletableFuture<Response> =
        next.processAsync().thenApply { response ->
            if (!response.status.isError) {
                response
            } else {
                // throwOnError buffers the bounded error body and throws the mapped exception;
                // thenApply routes the throw into the returned future as an exceptional completion.
                response.throwOnError()
            }
        }
}
