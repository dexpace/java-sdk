package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

/**
 * Chain accessor passed to each [HttpStep.process] call. Invoke [process] to drive the
 * rest of the pipeline; invoke [copy] before re-driving more than once (retry / redirect).
 *
 * Single instance per `pipeline.send(...)` call. Cloning is cheap — one [PipelineCallState]
 * allocation per [copy] — and the underlying steps array is shared across copies.
 */
class PipelineNext internal constructor(private val state: PipelineCallState) {

    /**
     * Advances to the next step and invokes it. If no further step exists, dispatches the
     * request to the pipeline's [HttpClient].
     */
    @Throws(IOException::class)
    fun process(): Response {
        val nextStep = state.advance()
        return if (nextStep == null) state.httpClient.execute(state.request)
        else nextStep.process(state.request, this)
    }

    /**
     * Returns a fresh [PipelineNext] rooted at the current cursor position. Required for
     * any step that re-invokes the downstream chain more than once — re-using `this` would
     * advance past steps already visited on the previous invocation.
     */
    fun copy(): PipelineNext = PipelineNext(state.copy())
}
