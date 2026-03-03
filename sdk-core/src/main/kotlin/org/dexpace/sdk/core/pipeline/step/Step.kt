package org.dexpace.sdk.core.pipeline.step


/**
 * Represents a single executable step in a pipeline workflow.
 *
 * Steps are designed to be part of a chain within a pipeline, where they define
 * specific actions that can be executed sequentially or conditionally. Each step
 * is associated with a configuration defined by [StepConfig].
 *
 * @param T The specific type of the step, which must extend the [Step] interface.
 */
interface Step<T : Step<T>> {
    /**
     * Executes the current step in the pipeline.
     *
     * The execution process follows the configuration defined in the associated
     * `StepConfig`, including properties like retry behavior, logging, and tags.
     * This method triggers the processing logic encapsulated by the step, allowing
     * it to perform its intended operation as part of the pipeline workflow.
     *
     * @return A [Result] wrapping the outcome of the execution. The returned value
     *         is of type `Nothing?`, typically indicating a lack of a meaningful
     *         result from the step, as its primary role is procedural in nature.
     */
    fun execute(): Result<Nothing?> {
        return Result.success(null)
    }

    /**
     * Holds the configuration for the current step in the pipeline.
     *
     * This property provides access to configurable attributes, such as the step's
     * name, description, version, retry configuration, and tags. The configuration
     * is associated with the specific type of step that implements the `Step` interface.
     *
     * The configuration can be used to customize the behavior and metadata
     * associated with the step during its execution within the pipeline.
     *
     * @see StepConfig
     * @see StepRetryConfig
     */
    val config: StepConfig<T>
}
