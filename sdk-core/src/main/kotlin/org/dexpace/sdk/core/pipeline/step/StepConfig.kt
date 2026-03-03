package org.dexpace.sdk.core.pipeline.step

/**
 * Represents the configuration for a step in a pipeline.
 *
 * This interface provides configurable properties for a step, such as its
 * name, description, versioning, retry mechanism, and associated tags.
 *
 * @param T The specific type of the step associated with this configuration,
 *          constrained to extend the [Step] interface.
 */
interface StepConfig<out T : Step<*>> {
    /**
     * Represents the step instance associated with the configuration.
     */
    val step: T

    /**
     * The name associated with a specific step configuration.
     */
    val name: String
        get() = "Default name"

    /**
     * A textual description to identify or document the purpose of a step in the pipeline.
     *
     * Used for logging and documentation purposes.
     */
    val description: String
        get() = "Default description"

    /**
     * The version identifier for a step configuration in the pipeline.
     */
    val version: String
        get() = "Default version"

    /**
     * Represents a list of metadata tags that categorize or describe a step within a pipeline.
     *
     * By default, the list is empty, indicating that no tags are associated with the step.
     */
    val tags: List<String>
        get() = emptyList()

    /**
     * Provides the retry configuration for a pipeline step.
     *
     * By default, it returns an instance of `StepRetryConfig` with default values.
     */
    val retry: StepRetryConfig
        get() = object : StepRetryConfig {}

    /**
     * The log message associated with a step in the pipeline.
     */
    val logMessage: String
        get() = "Default log message"
}
