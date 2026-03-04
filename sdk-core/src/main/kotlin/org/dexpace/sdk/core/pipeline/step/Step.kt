package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.pipeline.PipelineContext


/**
 * Represents a single executable step in a pipeline workflow.
 *
 * Steps are designed to be part of a chain within a pipeline, where they define
 * specific actions that can be executed sequentially or conditionally. Each step
 * is associated with a configuration defined by [StepConfigTrait].
 */
interface Step {
    /**
     * Executes the current step in the pipeline.
     *
     * @return A [Result] wrapping the outcome of the execution. The returned value
     *         is of type `Nothing?`, typically indicating a lack of a meaningful
     *         result from the step, as its primary role is procedural in nature.
     */
    fun execute(context: PipelineContext): Result<Nothing?>
}

/**
 * Extends the base [Step] by adding logging capabilities to a pipeline step.
 */
interface StepLoggingTrait : Step {
    fun log()
}

/**
 * Provides retry capabilities for a pipeline step.
 */
interface StepRetryTrait : Step {
    /**
     * Retries the execution of a step in the pipeline.
     *
     * @return A [Result] wrapping the outcome of the retry operation. The result
     *         is of type `Nothing?`, typically used to indicate the absence of a
     *         meaningful return value while signaling success or failure of the retry.
     */
    fun retry(): Result<Nothing?>
}
