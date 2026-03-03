package org.dexpace.sdk.core.pipeline.step

/**
 * Defines the retry configuration for a step in a pipeline. This configuration allows customization
 * of retry behavior in scenarios where a step execution encounters errors or transient failures.
 */
interface StepRetryConfig {
    /**
     * Indicates whether retries are enabled for the step in the pipeline.
     *
     * When set to `true`, the retry mechanism is activated, allowing the step to
     * attempt re-execution in case of failures.
     *
     * Default value is `false`, meaning retries are disabled by default.
     */
    val enabled: Boolean
        get() = false

    /**
     * The maximum time, in milliseconds, allowed for a retry operation to complete
     * within the configured step execution process.
     *
     * This value sets an upper boundary for how long a retry mechanism should
     * wait before considering a timeout scenario during the execution of a step.
     */
    val timeoutMilliseconds: Long
        get() = 10000

    /**
     * Indicates whether an exponential backoff strategy is enabled for retrying steps.
     *
     * Default value is `false`.
     */
    val exponentialBackoff: Boolean
        get() = false

    /**
     * The initial backoff time in milliseconds for retrying a step in the pipeline.
     */
    val initialBackoffMilliseconds: Long
        get() = 1000

    /**
     * The maximum allowed backoff duration in milliseconds for retry attempts.
     */
    val maxBackoffMilliseconds: Long
        get() = 10000

    /**
     * A multiplier value used for incrementally adjusting delays or backoff times, typically
     * in retry mechanisms.
     */
    val multiplier: Double
        get() = 2.0

    /**
     * Specifies the maximum number of retry attempts allowed for a step when an error occurs.
     *
     * This value determines how many times a step will retry execution before failing permanently.
     * It is used in conjunction with backoff and retry configurations to control the retry mechanism.
     */
    val maxRetries: Int
        get() = 3

    /**
     * Specifies the list of exception types that should trigger a retry mechanism
     * when encountered during the execution of a step.
     *
     * If no exceptions are specified, the retry mechanism will effectively be disabled.
     */
    val retryOn: List<Class<out Throwable>>
        get() = listOf(Exception::class.java)
}
