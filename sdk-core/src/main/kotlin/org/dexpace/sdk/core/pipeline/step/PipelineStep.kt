package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.context.ExchangeContext


/**
 * Represents a single executable step in a pipeline workflow.
 *
 * Steps are designed to be part of a chain within a pipeline, where they define
 * specific actions that can be executed sequentially or conditionally. Each step
 * is associated with a configuration defined by [StepConfigTrait].
 */
fun interface PipelineStep<in T, out V> {
    /**
     * Executes the current step in the pipeline.
     *
     * @return A [Result] wrapping the outcome of the execution. The returned value
     *         is of type `Nothing?`, typically indicating a lack of a meaningful
     *         result from the step, as its primary role is procedural in nature.
     */
    fun execute(input: T, context: DispatchContext): V
}

/**
 * Provides retry capabilities for a pipeline step.
 */
interface StepRetryTrait<in T, out V> : PipelineStep<T, V> {
    /**
     * Retries the execution of a step in the pipeline.
     *
     * @return A [Result] wrapping the outcome of the retry operation. The result
     *         is of type `Nothing?`, typically used to indicate the absence of a
     *         meaningful return value while signaling success or failure of the retry.
     */
    fun retry(context: ExchangeContext): V
}

interface RequestPipelineStep : PipelineStep<Request, Request>

interface ResponsePipelineStep : PipelineStep<Response, Response>
