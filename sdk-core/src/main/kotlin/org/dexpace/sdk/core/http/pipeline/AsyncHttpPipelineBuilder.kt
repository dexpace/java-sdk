/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.instrumentation.ClientLogger

/**
 * Async counterpart of [HttpPipelineBuilder]. Mirrors the same API verbatim but typed over
 * [AsyncHttpStep] / [AsyncHttpClient]. The step-storage and surgical-edit policy lives in the
 * shared [StagedSteps] helper.
 */
public class AsyncHttpPipelineBuilder(private val httpClient: AsyncHttpClient) {
    private val steps: StagedSteps<AsyncHttpStep> =
        StagedSteps(
            stageOf = AsyncHttpStep::stage,
            onPillarReplaced = { stage, prev, next ->
                LOG.atWarning()
                    .event("pipeline.pillar.replaced")
                    .field("pipeline.kind", "async")
                    .field("stage", stage.name)
                    .field("previous", prev::class.simpleName ?: "<anonymous>")
                    .field("replacement", next::class.simpleName ?: "<anonymous>")
                    .log()
            },
        )

    /** Append [step] at the tail of its stage's deque (runs after steps already there). */
    public fun append(step: AsyncHttpStep): AsyncHttpPipelineBuilder = apply { steps.append(step) }

    /** Prepend [step] at the head of its stage's deque (runs before steps already there). */
    public fun prepend(step: AsyncHttpStep): AsyncHttpPipelineBuilder = apply { steps.prepend(step) }

    /** Appends every step in [batch] via [append], preserving iteration order. */
    public fun appendAll(batch: Iterable<AsyncHttpStep>): AsyncHttpPipelineBuilder = apply { batch.forEach(::append) }

    /** Prepends every step in [batch] via [prepend], preserving iteration order (final order is reversed). */
    public fun prependAll(batch: Iterable<AsyncHttpStep>): AsyncHttpPipelineBuilder = apply { batch.forEach(::prepend) }

    /**
     * Insert [step] immediately after the first instance of [T] in the pipeline.
     *
     * Placement is **within [T]'s stage only**: [step] must declare the same [Stage] as the
     * matched anchor. A cross-stage insert throws [IllegalArgumentException] rather than
     * silently relocating [step] to wherever its own stage falls. Route a different-stage step
     * with [append] / [prepend] instead.
     */
    public inline fun <reified T : AsyncHttpStep> insertAfter(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        insertAfter(T::class.java, step)

    /**
     * Insert [step] immediately before the first instance of [T] in the pipeline. Same
     * within-stage-only constraint as [insertAfter] — see its KDoc.
     */
    public inline fun <reified T : AsyncHttpStep> insertBefore(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        insertBefore(T::class.java, step)

    /**
     * Replace the first instance of [T] with [step]. [step] must declare the same [Stage] as
     * the replaced step; a cross-stage replacement throws [IllegalArgumentException].
     */
    public inline fun <reified T : AsyncHttpStep> replace(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        replace(T::class.java, step)

    /** Remove every instance of [T] from the pipeline. No-op if none exist. */
    public inline fun <reified T : AsyncHttpStep> remove(): AsyncHttpPipelineBuilder = remove(T::class.java)

    /** Non-inline body for [insertAfter]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun insertAfter(
        anchorType: Class<out AsyncHttpStep>,
        step: AsyncHttpStep,
    ): AsyncHttpPipelineBuilder = apply { steps.insertRelativeToFirst(anchorType, step, InsertPosition.AFTER) }

    /** Non-inline body for [insertBefore]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun insertBefore(
        anchorType: Class<out AsyncHttpStep>,
        step: AsyncHttpStep,
    ): AsyncHttpPipelineBuilder = apply { steps.insertRelativeToFirst(anchorType, step, InsertPosition.BEFORE) }

    /** Non-inline body for [replace]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun replace(
        anchorType: Class<out AsyncHttpStep>,
        step: AsyncHttpStep,
    ): AsyncHttpPipelineBuilder = apply { steps.replaceFirst(anchorType, step) }

    /** Non-inline body for [remove]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun remove(anchorType: Class<out AsyncHttpStep>): AsyncHttpPipelineBuilder =
        apply { steps.removeMatching(anchorType) }

    /** Builds an immutable [AsyncHttpPipeline]. */
    public fun build(): AsyncHttpPipeline {
        val ordered = steps.flatten()
        val array = arrayOfNulls<AsyncHttpStep>(ordered.size)
        for ((i, s) in ordered.withIndex()) array[i] = s
        @Suppress("UNCHECKED_CAST")
        return AsyncHttpPipeline(httpClient, array as Array<AsyncHttpStep>)
    }

    public companion object {
        private val LOG = ClientLogger(AsyncHttpPipelineBuilder::class)

        /** Returns a new builder seeded with [pipeline]'s steps and client. */
        @JvmStatic
        public fun from(pipeline: AsyncHttpPipeline): AsyncHttpPipelineBuilder =
            AsyncHttpPipelineBuilder(pipeline.httpClient).also { it.steps.reload(pipeline.steps) }
    }
}
