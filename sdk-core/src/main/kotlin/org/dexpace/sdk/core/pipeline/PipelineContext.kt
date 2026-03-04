package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.UUID

/**
 * Represents the execution context of a pipeline.
 * This interface provides access to key components required during the execution of a pipeline,
 * such as the pipeline itself, the associated request and response,
 * and a unique identifier for the current pipeline execution run.
 */
interface PipelineContext {
    /**
     * Represents the pipeline associated with a specific execution context.
     */
    val pipeline: Pipeline

    /**
     * Represents the request associated with the current pipeline execution context.
     */
    val request: Request
    /**
     * Represents the `Response` object associated with the current pipeline execution context.
     */
    val response: Response

    /**
     * A unique identifier for the execution of a specific pipeline instance.
     */
    val executionId: String
        get() = "${pipeline.javaClass.name}-${System.currentTimeMillis()}-${UUID.randomUUID()}"
}
