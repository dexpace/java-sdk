package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.BuilderTrait

class BuilderPipeline<T> private constructor(
    private val steps: List<(BuilderTrait<T>, DispatchContext) -> BuilderTrait<T>> = emptyList(),
    private val builderFactory: () -> BuilderTrait<T>,
) {

    init {
        require(steps.isNotEmpty()) { "Pipeline must have at least one step" }
    }

    fun build(context: DispatchContext): T = steps
        .fold(builderFactory()) { builder, step -> step(builder, context) }
        .build()

    companion object {
        @JvmStatic
        fun <T> of(
            steps: List<(BuilderTrait<T>, DispatchContext) -> BuilderTrait<T>> = emptyList(),
            builderFactory: () -> BuilderTrait<T>
        ): BuilderPipeline<T> {
            return BuilderPipeline(steps, builderFactory)
        }
    }
}
