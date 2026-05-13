package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.instrumentation.ClientLogger

/**
 * Async counterpart of [HttpPipelineBuilder]. Mirrors the same API verbatim but typed over
 * [AsyncHttpStep] / [AsyncHttpClient]. The step-storage and surgical-edit policy lives in the
 * shared [StagedSteps] helper.
 */
class AsyncHttpPipelineBuilder(private val httpClient: AsyncHttpClient) {

    @PublishedApi
    internal val steps: StagedSteps<AsyncHttpStep> = StagedSteps(
        stageOf = AsyncHttpStep::stage,
        onPillarReplaced = { stage, prev, next ->
            LOG.atWarning()
                .event("async.pipeline.pillar.replaced")
                .field("stage", stage.name)
                .field("previous", prev::class.simpleName ?: "<anonymous>")
                .field("replacement", next::class.simpleName ?: "<anonymous>")
                .log()
        },
    )

    /** Append [step] at the tail of its stage's deque (runs after steps already there). */
    fun append(step: AsyncHttpStep): AsyncHttpPipelineBuilder = apply { steps.append(step) }

    /** Prepend [step] at the head of its stage's deque (runs before steps already there). */
    fun prepend(step: AsyncHttpStep): AsyncHttpPipelineBuilder = apply { steps.prepend(step) }

    /** Appends every step in [batch] via [append], preserving iteration order. */
    fun appendAll(batch: Iterable<AsyncHttpStep>): AsyncHttpPipelineBuilder = apply { batch.forEach(::append) }

    /** Prepends every step in [batch] via [prepend], preserving iteration order (final order is reversed). */
    fun prependAll(batch: Iterable<AsyncHttpStep>): AsyncHttpPipelineBuilder = apply { batch.forEach(::prepend) }

    /** Insert [step] immediately after the first instance of [T] in the pipeline. */
    inline fun <reified T : AsyncHttpStep> insertAfter(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<AsyncHttpStep>(flat.size + 1).apply {
                addAll(flat.subList(0, idx + 1))
                add(step)
                addAll(flat.subList(idx + 1, flat.size))
            }
        }

    /** Insert [step] immediately before the first instance of [T] in the pipeline. */
    inline fun <reified T : AsyncHttpStep> insertBefore(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<AsyncHttpStep>(flat.size + 1).apply {
                addAll(flat.subList(0, idx))
                add(step)
                addAll(flat.subList(idx, flat.size))
            }
        }

    /** Replace the first instance of [T] with [step]. */
    inline fun <reified T : AsyncHttpStep> replace(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<AsyncHttpStep>(flat.size).apply {
                addAll(flat)
                set(idx, step)
            }
        }

    /** Remove every instance of [T] from the pipeline. No-op if none exist. */
    inline fun <reified T : AsyncHttpStep> remove(): AsyncHttpPipelineBuilder {
        steps.reload(steps.flatten().filter { it !is T })
        return this
    }

    @PublishedApi
    internal inline fun <reified T : AsyncHttpStep> spliceAt(
        @Suppress("UNUSED_PARAMETER") step: AsyncHttpStep,
        transform: (Int, List<AsyncHttpStep>) -> List<AsyncHttpStep>,
    ): AsyncHttpPipelineBuilder {
        val flat = steps.flatten()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        steps.reload(transform(idx, flat))
        return this
    }

    /** Builds an immutable [AsyncHttpPipeline]. */
    fun build(): AsyncHttpPipeline {
        val ordered = steps.flatten()
        val array = arrayOfNulls<AsyncHttpStep>(ordered.size)
        for ((i, s) in ordered.withIndex()) array[i] = s
        @Suppress("UNCHECKED_CAST")
        return AsyncHttpPipeline(httpClient, array as Array<AsyncHttpStep>)
    }

    companion object {
        @PublishedApi
        internal val LOG = ClientLogger(AsyncHttpPipelineBuilder::class)

        /** Returns a new builder seeded with [pipeline]'s steps and client. */
        @JvmStatic
        fun from(pipeline: AsyncHttpPipeline): AsyncHttpPipelineBuilder =
            AsyncHttpPipelineBuilder(pipeline.httpClient).also { it.steps.reload(pipeline.steps) }
    }
}
