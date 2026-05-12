package org.dexpace.sdk.core.pipeline.step

import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Marker root for opt-in configuration mixins applied to a [PipelineStep].
 *
 * Configuration is decomposed into orthogonal traits ([PipelineStepMetadataTrait],
 * [PipelineStepRetryConfigTrait], ...) rather than a single monolithic config object — a step opts
 * into only the facets it cares about. This keeps the public API of trivial steps minimal while
 * letting feature-rich steps surface their knobs through stable interfaces.
 */
interface PipelineStepConfigTrait

/**
 * Mixin that attaches human-readable metadata (name, description, version, tags) to a step. Used by
 * logging, tracing, and tooling that needs to identify a step at runtime.
 */
interface PipelineStepMetadataTrait : PipelineStepConfigTrait {
    /** Stable identifier for this step. Override to surface a meaningful name in logs and traces. */
    val name: String
        get() = "Default name"

    /** Free-form description of what the step does. Surfaced in logs and tooling. */
    val description: String
        get() = "Default description"

    /** Version string for the step's behavior. Bump when behavior changes in a non-back-compatible way. */
    val version: String
        get() = "Default version"

    /** Optional category / capability tags. Defaults to an empty list when no tags apply. */
    val tags: List<String>
        get() = emptyList()
}

/**
 * Mixin describing how a step should be retried on failure. Defaults provide a reasonable
 * exponential-backoff-disabled fixed-delay policy; override individual members to tune.
 */
interface PipelineStepRetryConfigTrait : PipelineStepConfigTrait {
    /** Per-attempt timeout ceiling in milliseconds. Overshoots are treated as failures by the retry driver. */
    val timeoutMilliseconds: Long
        get() = 10000

    /** When true, the backoff between attempts grows geometrically by [multiplier]; otherwise fixed at [initialBackoffMilliseconds]. */
    val exponentialBackoff: Boolean
        get() = false

    /** Initial backoff between retries in milliseconds. Also the constant backoff when [exponentialBackoff] is false. */
    val initialBackoffMilliseconds: Long
        get() = 1000

    /** Upper bound on the per-attempt backoff. Prevents [exponentialBackoff] from producing pathologically long delays. */
    val maxBackoffMilliseconds: Long
        get() = 10000

    /** Geometric growth factor applied when [exponentialBackoff] is true. */
    val multiplier: Double
        get() = 2.0

    /** Maximum retry attempts before the failure propagates to the caller. */
    val maxRetries: Int
        get() = 3

    /**
     * Throwable types that trigger a retry. Throwables outside this list fail fast; an empty list
     * effectively disables retry. Defaults to `IOException` and `TimeoutException` to match the
     * classifier used by [org.dexpace.sdk.core.util.RetryUtils.isRetryable] — programmer errors
     * (`IllegalStateException`, `NullPointerException`, …) are intentionally excluded so retries
     * don't paper over bugs.
     */
    val retryOnExceptions: List<Class<out Throwable>>
        get() = listOf(IOException::class.java, TimeoutException::class.java)
}
