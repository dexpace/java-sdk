package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.concurrent.CompletableFuture

/**
 * Asynchronous counterpart of [HttpStep] — a pipeline item whose [processAsync] returns a
 * [CompletableFuture] instead of blocking. Same stage semantics ([Stage]); same composition
 * rules (pillar stages admit exactly one step, non-pillar stages admit any number).
 *
 * Use this interface when the step itself performs async work — e.g. a bearer-token refresh
 * that calls an OAuth endpoint via an [AsyncHttpClient], or a retry that schedules its delay
 * on a [java.util.concurrent.ScheduledExecutorService] instead of blocking.
 *
 * For pure passthrough steps (header stamping, URL redaction, etc.) that have no async work
 * of their own, prefer the synchronous [HttpStep] and use [HttpPipeline.toAsync] from
 * [AsyncPipelineBridges][org.dexpace.sdk.core.http.pipeline] to wrap the whole pipeline
 * at the boundary — reimplementing the step async is rarely worth the duplication.
 *
 * ## Thread-safety
 * Like [HttpStep], implementations are shared across concurrent pipeline calls — instance
 * state must be safe for concurrent invocation. Per-request state belongs in the
 * `AsyncPipelineCallState` (cloned via [AsyncPipelineNext.copy]).
 */
public interface AsyncHttpStep {
    /**
     * Processes [request] and returns a future that completes with the [Response]. May call
     * [next] zero or more times — short-circuit by completing with a synthetic [Response];
     * re-invoke downstream multiple times via [AsyncPipelineNext.copy] (required for async
     * retry / redirect).
     *
     * Implementations MUST NOT throw synchronously for transport failures — return a
     * future that completes exceptionally instead. Argument-validation exceptions
     * (`IllegalArgumentException`, `NullPointerException`) MAY be thrown synchronously
     * because they indicate caller bugs, not async failures.
     */
    public fun processAsync(request: Request, next: AsyncPipelineNext): CompletableFuture<Response>

    /** The pipeline stage this step occupies. */
    public val stage: Stage
}
