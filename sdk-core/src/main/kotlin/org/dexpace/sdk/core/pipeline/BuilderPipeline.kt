package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.generics.BuilderTrait
import org.dexpace.sdk.core.http.context.DispatchContext

class BuilderPipeline<T>(
    val steps: List<(BuilderTrait<T>, DispatchContext) -> BuilderTrait<T>> = emptyList(),
    val builderFactory: () -> BuilderTrait<T>,
) {

    init {
        require(steps.isNotEmpty()) { "Pipeline must have at least one step" }
    }

    fun build(context: DispatchContext): T = steps
        .fold(builderFactory()) { builder, step -> step(builder, context) }
        .build()
}
