package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.pipeline.step.Step


/**
 * Defines a pipeline structure for executing a sequence of steps within a given context.
 *
 * A pipeline represents a modular and sequential workflow, where each step is executed in order
 * with the same shared context. The pipeline is responsible for orchestrating the execution
 * of its steps and handling any exceptions that may occur during execution.
 *
 * TODO: The whole pipeline thing works based on the OOP concept of mutating request/response
 *  instances in-place. This limits the retry ability of any failing step. Let's think about
 *  how we can make it more flexible by utilizing pure functional programming concepts.
 *  We could also introduce a dependency definition capability between steps to be able to
 *  revert request/response to a previous state in case of failure or corruption.
 */
interface Pipeline {
    /**
     * Executes the pipeline by processing each step in sequence with the given execution context.
     * If any step fails during execution, the pipeline halts, and the failure is captured as a [Result].
     *
     * @param context The [PipelineContext] that provides the execution environment, including
     *                the pipeline, request, response, and execution identifier.
     * @return A [Result] representing the outcome of the pipeline execution. If all steps succeed,
     *         the result will be a success with a `null` value. If an exception occurs during execution,
     *         the result will be a failure wrapping the exception.
     */
    fun execute(context: PipelineContext): Result<Nothing?> =
        try {
            steps.forEach { step -> step.execute(context) }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Represents an ordered sequence of steps that constitute the workflow of a pipeline.
     *
     * Each step in the pipeline defines a specific action to be executed, and the steps are processed
     * sequentially in the order they are listed. The steps are associated with the pipeline, and their
     * execution is managed through the pipeline's context.
     *
     * The primary role of the `steps` property is to provide access to the list of defined steps,
     * enabling their execution within the pipeline's lifecycle.
     */
    val steps: List<Step>
        get() = emptyList()

    /**
     * Represents the current execution context of the pipeline.
     *
     * This property provides access to the [PipelineContext], which includes data and state necessary
     * during pipeline execution, such as the associated request, response, and unique execution identifier.
     * It is used throughout the pipeline's lifecycle to share execution-related information between steps.
     */
    val context: PipelineContext
}
