package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.pipeline.step.Step


interface Pipeline<in T, out R> {
    fun run(input: T, context: PipelineContext<*>): R

    val steps: List<Step<*>>
        get() = emptyList()

    // TODO: Consider engine based context where we can support coroutine contexts, thread context, ...etc
    val context: PipelineContext<*>
}
