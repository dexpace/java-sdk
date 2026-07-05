/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseHandler
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

/**
 * Asynchronous counterpart of [HttpPipeline]. Holds an ordered, stage-sorted [Array] of
 * [AsyncHttpStep]s and an [AsyncHttpClient] transport; [sendAsync] walks the chain in order
 * and dispatches the request to the client when the chain is exhausted.
 *
 * ## Thread-safety
 * The steps array and client reference are immutable after construction; each [sendAsync]
 * allocates exactly one [AsyncPipelineCallState] + one [AsyncPipelineNext] per call, so
 * concurrent sends never share mutable state inside the pipeline itself. Steps must remain
 * thread-safe across concurrent invocations per the pipeline contract.
 *
 * ## Transport SPI conformance
 * `AsyncHttpPipeline` itself implements [AsyncHttpClient]: [executeAsync] delegates to
 * [sendAsync]. A fully configured pipeline can therefore stand in anywhere an async transport is
 * expected — most usefully as the client backing an
 * [org.dexpace.sdk.core.pagination.AsyncPaginator]. The inherited [close] is a no-op: the
 * pipeline wraps a caller-supplied transport it does not own, so it never closes it.
 *
 * Construct via [AsyncHttpPipelineBuilder]; the constructor is internal.
 */
public class AsyncHttpPipeline internal constructor(
    /** The transport that receives the request when no further step remains. */
    public val httpClient: AsyncHttpClient,
    internal val stepArray: Array<AsyncHttpStep>,
) : AsyncHttpClient {
    /** Read-only list view of the ordered steps. Backed by [stepArray]; for inspection only. */
    public val steps: List<AsyncHttpStep> = stepArray.asList()

    /**
     * Runs [request] through the pipeline. Empty pipelines short-circuit directly to
     * [AsyncHttpClient.executeAsync] with no per-call allocation beyond the transport's own
     * future.
     *
     * The returned future completes with the downstream chain's final response (which may be
     * synthesised by a short-circuiting step) or completes exceptionally with the transport
     * or step failure. Synchronous exceptions thrown by the first step are normalised into
     * a failed future so callers see a uniform async error model.
     */
    public fun sendAsync(request: Request): CompletableFuture<Response> = sendAsync(request, RequestOptions.EMPTY)

    /**
     * Runs [request] through the pipeline with per-call [options] applied. The options are carried
     * for the whole call: async steps read them via [AsyncPipelineNext.options] (the async stage
     * retry step honours a per-call [RequestOptions.maxRetries]), and the terminal dispatch threads
     * them into `AsyncHttpClient.executeAsync(request, options)` so the transport applies a per-call
     * timeout. Empty pipelines short-circuit directly to the transport's per-call overload.
     */
    public fun sendAsync(
        request: Request,
        options: RequestOptions,
    ): CompletableFuture<Response> {
        if (stepArray.isEmpty()) {
            return try {
                httpClient.executeAsync(request, options)
            } catch (e: Exception) {
                // Defensive: the AsyncHttpClient contract says transport failures complete
                // the future exceptionally. Normalise any sync throw to keep the contract
                // uniform. `Error` (OOM, StackOverflow) propagates per style guide §8.3.
                Futures.failed(e)
            }
        }
        val state = AsyncPipelineCallState(this, request, options)
        return AsyncPipelineNext(state).processAsync()
    }

    /**
     * Runs [request] through the pipeline and maps the response with [handler], returning the
     * typed result. A terminal convenience equivalent to `sendAsync(request).handleWith(handler)`:
     * on success the handler's value completes the future and the response is closed; on failure
     * the future is failed with the unwrapped cause. See [handleWith] for the exact semantics.
     */
    public fun <T> sendAsync(
        request: Request,
        handler: ResponseHandler<T>,
    ): CompletableFuture<T> = sendAsync(request).handleWith(handler)

    /**
     * [AsyncHttpClient] SPI conformance: delegates to [sendAsync] so this pipeline can be used as
     * an async transport. [sendAsync] remains the primary entry point.
     */
    override fun executeAsync(request: Request): CompletableFuture<Response> = sendAsync(request)

    /**
     * [AsyncHttpClient] per-call SPI conformance: delegates to [sendAsync] so a caller's [options]
     * reach the pipeline (and the transport) even when the pipeline is used through the async
     * transport SPI.
     */
    override fun executeAsync(
        request: Request,
        options: RequestOptions,
    ): CompletableFuture<Response> = sendAsync(request, options)

    public companion object {
        /**
         * Builds a step-less [AsyncHttpPipeline] that forwards every `sendAsync` directly to
         * [client]. Equivalent to `AsyncHttpPipelineBuilder(client).build()` but avoids the
         * builder ceremony for the common case of "I just want a pipeline that calls the
         * client and nothing else." Used by [AsyncPipelineBridges][org.dexpace.sdk.core.http.pipeline]
         * to wrap a sync pipeline as async.
         */
        @JvmStatic
        public fun of(client: AsyncHttpClient): AsyncHttpPipeline = AsyncHttpPipelineBuilder(client).build()

        /**
         * Builds an async pipeline over [transport] wired with the async standard resilience
         * defaults — retry with backoff and instrumentation — via
         * [AsyncHttpPipelineBuilder.appendStandardResilience]. As documented there, there is no
         * async redirect default, and [scheduler] backs the retry step's non-blocking delays
         * (the caller owns and shuts it down; the SDK never closes it).
         */
        @JvmStatic
        public fun standard(
            transport: AsyncHttpClient,
            scheduler: ScheduledExecutorService,
        ): AsyncHttpPipeline = AsyncHttpPipelineBuilder(transport).appendStandardResilience(scheduler).build()
    }
}
