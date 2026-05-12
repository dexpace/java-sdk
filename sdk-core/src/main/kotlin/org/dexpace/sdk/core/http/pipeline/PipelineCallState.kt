package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request

/**
 * Per-call mutable cursor over a [HttpPipeline]'s steps array. Holds the index of the
 * next step to invoke, the originating [Request], and a reference to the [HttpClient] used
 * when the cursor reaches the end.
 *
 * Cloned via [copy] (exposed to user code through [PipelineNext.copy]) so retry / redirect
 * steps can re-drive the downstream chain. Cloning copies the current index — the new
 * state resumes from the same position, advancing independently.
 *
 * Backed by an [Array] of steps for tight iteration (per the pipeline performance
 * guardrails); index advance is a single field write.
 *
 * Internal: cloning is reachable only through [PipelineNext.copy].
 */
internal class PipelineCallState internal constructor(
    val pipeline: HttpPipeline,
    initialRequest: Request,
    val httpClient: HttpClient,
    private var index: Int = 0,
) {
    // Mutable so a step may substitute the downstream request (e.g. wrap the body in
    // LoggableRequestBody before send). The substitution propagates to all subsequent steps
    // and to the terminal HttpClient.execute(...) when the cursor reaches the end.
    var request: Request = initialRequest

    /** Returns the next step to invoke, or null if the cursor has reached the end. */
    fun advance(): HttpStep? {
        val steps = pipeline.stepArray
        return if (index < steps.size) steps[index++] else null
    }

    /** Returns an independent state cloned at the current cursor position. */
    fun copy(): PipelineCallState = PipelineCallState(pipeline, request, httpClient, index)
}
