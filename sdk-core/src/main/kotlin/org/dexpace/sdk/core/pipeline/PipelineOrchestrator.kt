package org.dexpace.sdk.core.pipeline

/**
 * Provides orchestration for executing pipelines by managing their execution contexts.
 *
 * The `PipelineOrchestrator` maintains a map of active pipeline contexts and ensures proper
 * setup and teardown of these contexts during a pipeline's execution. It also enforces
 * that pipeline execution contexts are unique and properly synchronized to avoid data
 * inconsistency or duplication.
 */
object PipelineOrchestrator {
    /**
     * A map that manages the associations between unique pipeline run IDs and their respective
     * [PipelineContext] instances.
     */
    private val contexts: MutableMap<String, PipelineContext> = mutableMapOf()

    /**
     * Registers the given `PipelineContext` to maintain execution state during pipeline processing.
     *
     * @param context The pipeline context to be registered. It contains execution-specific data
     *                including the `runId` that uniquely identifies the pipeline run.
     * @throws IllegalArgumentException if the `runId` already exists in the contexts map.
     */
    private fun setup(context: PipelineContext) {
        val runId = context.executionId
        require(!contexts.containsKey(runId)) { "Pipeline run id duplicated: $runId" }

        contexts[runId] = context
    }

    /**
     * Cleans up resources associated with a pipeline execution context.
     *
     * @param context The `PipelineContext` representing the pipeline execution to be cleaned up.
     *                This context includes the unique pipeline run ID used for tracking.
     */
    private fun teardown(context: PipelineContext) {
        val runId = context.executionId
        require(contexts.containsKey(runId)) { "Contexts map corrupted. Pipeline run id not found: $runId" }

        contexts.remove(runId)
    }

    /**
     * Executes a given pipeline with the provided input and manages the pipeline's execution context.
     *
     * @param pipeline The pipeline to be executed. It contains the steps and execution logic.
     * @return The result produced by the pipeline after processing the input.
     * @throws IllegalArgumentException If the context run ID is duplicated or missing during execution.
     */
    fun execute(pipeline: Pipeline) {
        val context = pipeline.context

        setup(context)
        pipeline.execute(context)
        teardown(context)
    }
}
