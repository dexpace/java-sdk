package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.util.Collections
import java.util.concurrent.CompletableFuture

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
 * Construct via [AsyncHttpPipelineBuilder]; the constructor is internal.
 */
public class AsyncHttpPipeline internal constructor(
    /** The transport that receives the request when no further step remains. */
    public val httpClient: AsyncHttpClient,
    internal val stepArray: Array<AsyncHttpStep>,
) {
    /** Unmodifiable list view of the ordered steps. Backed by [stepArray]; for inspection only. */
    public val steps: List<AsyncHttpStep> = Collections.unmodifiableList(stepArray.asList())

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
    public fun sendAsync(request: Request): CompletableFuture<Response> {
        if (stepArray.isEmpty()) {
            return try {
                httpClient.executeAsync(request)
            } catch (e: Exception) {
                // Defensive: the AsyncHttpClient contract says transport failures complete
                // the future exceptionally. Normalise any sync throw to keep the contract
                // uniform. `Error` (OOM, StackOverflow) propagates per style guide §8.3.
                Futures.failed(e)
            }
        }
        val state = AsyncPipelineCallState(this, request, httpClient)
        return AsyncPipelineNext(state).processAsync()
    }

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
    }
}
