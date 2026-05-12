package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

/**
 * A bidirectional pipeline item. Each step observes the [Request] on the way in, may call
 * [PipelineNext.process] to invoke the rest of the pipeline, and observes (or substitutes)
 * the resulting [Response] on the way out.
 *
 * Implementations declare their pipeline placement via [stage]. Pillar stages
 * ([Stage.isPillar]) admit exactly one step; non-pillar stages admit any number, ordered
 * by insertion via [HttpPipelineBuilder.append] / [HttpPipelineBuilder.prepend].
 *
 * Thread-safety: steps are shared across concurrent requests — implementations must be
 * safe to invoke from multiple threads. Per-request state belongs in [PipelineCallState]
 * (cloned via [PipelineNext.copy]), not on the step.
 *
 * Note: declared as a regular [interface] rather than `fun interface` — Kotlin disallows
 * functional interfaces with abstract properties, and [stage] must be declared by every
 * step.
 */
interface HttpStep {
    /**
     * Processes [request] and returns the [Response]. May call [next] zero or more times
     * — short-circuit by returning a synthetic [Response]; re-invoke downstream multiple
     * times via [PipelineNext.copy] (required for retry / redirect).
     */
    @Throws(IOException::class)
    fun process(request: Request, next: PipelineNext): Response

    /** The pipeline stage this step occupies. */
    val stage: Stage
}
