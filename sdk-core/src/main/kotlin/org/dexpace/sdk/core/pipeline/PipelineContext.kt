package org.dexpace.sdk.core.pipeline

import java.util.UUID

interface PipelineContext<T : Pipeline<*, *>> {
    val pipeline: T

    val runId: String
        get() = "${pipeline.javaClass.name}-${System.currentTimeMillis()}-${UUID.randomUUID()}"
}
