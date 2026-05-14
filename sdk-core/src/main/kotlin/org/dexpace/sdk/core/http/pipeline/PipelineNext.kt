package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

/**
 * Chain accessor passed to each [HttpStep.process] call. Invoke [process] to drive the
 * rest of the pipeline; invoke [copy] before re-driving more than once (retry / redirect).
 *
 * Single instance per `pipeline.send(...)` call. Cloning is cheap — one [PipelineCallState]
 * allocation per [copy] — and the underlying steps array is shared across copies.
 */
public class PipelineNext internal constructor(private val state: PipelineCallState) {
    /**
     * Advances to the next step and invokes it. If no further step exists, dispatches the
     * request to the pipeline's [org.dexpace.sdk.core.client.HttpClient].
     */
    @Throws(IOException::class)
    public fun process(): Response {
        val nextStep = state.advance()
        return if (nextStep == null) {
            state.httpClient.execute(state.request)
        } else {
            nextStep.process(state.request, this)
        }
    }

    /**
     * Substitutes the in-flight request with [request] before advancing to the next step.
     * The substitution sticks: every downstream step (and the terminal `HttpClient.execute`)
     * sees [request] in place of the original. Used by steps that need to transform the
     * outgoing request — e.g. [org.dexpace.sdk.core.http.pipeline.steps.InstrumentationStep]
     * wrapping the body in `LoggableRequestBody` so the bytes can be captured for logging.
     */
    @Throws(IOException::class)
    public fun process(request: Request): Response {
        state.request = request
        return process()
    }

    /**
     * Returns a fresh [PipelineNext] rooted at the current cursor position. Required for
     * any step that re-invokes the downstream chain more than once — re-using `this` would
     * advance past steps already visited on the previous invocation.
     */
    public fun copy(): PipelineNext = PipelineNext(state.copy())
}
