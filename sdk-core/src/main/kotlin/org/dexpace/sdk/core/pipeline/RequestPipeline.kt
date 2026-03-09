package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.pipeline.step.RequestPipelineStep

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
fun interface RequestPipeline {

    /**
     * Executes the pipeline by processing each step in sequence with the given execution context.
     * If any step fails during execution, the pipeline halts, and the failure is captured as a [Result].
     *
     * @param context The [DispatchContext] that provides the execution environment, including
     *                the pipeline, request, response, and execution identifier.
     * @return A [Result] representing the outcome of the pipeline execution. If all steps succeed,
     *         the result will be a success with a `null` value. If an exception occurs during execution,
     *         the result will be a failure wrapping the exception.
     */
    fun execute(request: Request, context: DispatchContext): Request

    val steps: List<RequestPipelineStep>
        get() = emptyList()
}
