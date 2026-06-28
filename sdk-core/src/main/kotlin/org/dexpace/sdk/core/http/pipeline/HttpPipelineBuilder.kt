/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.instrumentation.ClientLogger

/**
 * Mutable builder for [HttpPipeline]. Steps are organised by their declared [Stage]:
 * pillar stages hold a single instance (with replace-emits-warning semantics); non-pillar
 * stages hold an ordered deque.
 *
 * Primary API ([append] / [prepend]) routes a step by its declared stage. Surgical API
 * ([insertAfter] / [insertBefore] / [replace] / [remove]) operates relative to existing
 * step types, useful for tweaking an already-built pipeline via [from].
 *
 * Thread-safety: not thread-safe. Construct on one thread, then call [build] — the resulting
 * [HttpPipeline] is immutable.
 *
 * Logging: pillar replacement emits a `pipeline.pillar.replaced` warning via SLF4J.
 */
public class HttpPipelineBuilder(private val httpClient: HttpClient) {
    private val steps: StagedSteps<HttpStep> =
        StagedSteps(
            stageOf = HttpStep::stage,
            onPillarReplaced = { stage, prev, next ->
                LOG.atWarning()
                    .event("pipeline.pillar.replaced")
                    .field("stage", stage.name)
                    .field("previous", prev::class.simpleName ?: "<anonymous>")
                    .field("replacement", next::class.simpleName ?: "<anonymous>")
                    .log()
            },
        )

    /** Append [step] at the tail of its stage's deque (runs after steps already there). */
    public fun append(step: HttpStep): HttpPipelineBuilder = apply { steps.append(step) }

    /** Prepend [step] at the head of its stage's deque (runs before steps already there). */
    public fun prepend(step: HttpStep): HttpPipelineBuilder = apply { steps.prepend(step) }

    /** Appends every step in [batch] via [append], preserving iteration order. */
    public fun appendAll(batch: Iterable<HttpStep>): HttpPipelineBuilder = apply { batch.forEach(::append) }

    /**
     * Prepends every step in [batch] via [prepend], preserving iteration order. Note that
     * because each call uses [prepend], the final relative ordering inside each stage is the
     * reverse of [batch] — the last item ends up at the head.
     */
    public fun prependAll(batch: Iterable<HttpStep>): HttpPipelineBuilder = apply { batch.forEach(::prepend) }

    /**
     * Insert [step] immediately after the first instance of [T] in the pipeline.
     *
     * Placement is **within [T]'s stage only**: [step] must declare the same [Stage] as the
     * matched anchor. A cross-stage insert throws [IllegalArgumentException] rather than
     * silently relocating [step] to wherever its own stage falls (steps are re-bucketed by
     * stage on every edit). Route a different-stage step with [append] / [prepend] instead.
     */
    public inline fun <reified T : HttpStep> insertAfter(step: HttpStep): HttpPipelineBuilder =
        insertAfter(T::class.java, step)

    /**
     * Insert [step] immediately before the first instance of [T] in the pipeline. Same
     * within-stage-only constraint as [insertAfter] — see its KDoc.
     */
    public inline fun <reified T : HttpStep> insertBefore(step: HttpStep): HttpPipelineBuilder =
        insertBefore(T::class.java, step)

    /**
     * Replace the first instance of [T] with [step]. [step] must declare the same [Stage] as
     * the replaced step; a cross-stage replacement throws [IllegalArgumentException].
     */
    public inline fun <reified T : HttpStep> replace(step: HttpStep): HttpPipelineBuilder = replace(T::class.java, step)

    /** Remove every instance of [T] from the pipeline. No-op if none exist. */
    public inline fun <reified T : HttpStep> remove(): HttpPipelineBuilder = remove(T::class.java)

    /** Non-inline body for [insertAfter]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun insertAfter(
        anchorType: Class<out HttpStep>,
        step: HttpStep,
    ): HttpPipelineBuilder = apply { steps.insertRelativeToFirst(anchorType, step, InsertPosition.AFTER) }

    /** Non-inline body for [insertBefore]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun insertBefore(
        anchorType: Class<out HttpStep>,
        step: HttpStep,
    ): HttpPipelineBuilder = apply { steps.insertRelativeToFirst(anchorType, step, InsertPosition.BEFORE) }

    /** Non-inline body for [replace]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun replace(
        anchorType: Class<out HttpStep>,
        step: HttpStep,
    ): HttpPipelineBuilder = apply { steps.replaceFirst(anchorType, step) }

    /** Non-inline body for [remove]; keeps [StagedSteps] off the public API surface. */
    @PublishedApi
    internal fun remove(anchorType: Class<out HttpStep>): HttpPipelineBuilder =
        apply { steps.removeMatching(anchorType) }

    /**
     * Builds an immutable [HttpPipeline] in stage order. [Stage.SEND] is reserved for the
     * transport and is skipped.
     */
    public fun build(): HttpPipeline {
        val ordered = steps.flatten()
        return HttpPipeline(httpClient, ordered.toTypedArray())
    }

    public companion object {
        private val LOG = ClientLogger(HttpPipelineBuilder::class)

        /** Returns a new builder seeded with [pipeline]'s steps and client. */
        @JvmStatic
        public fun from(pipeline: HttpPipeline): HttpPipelineBuilder =
            HttpPipelineBuilder(pipeline.httpClient)
                .also { it.steps.reload(pipeline.steps) }
    }
}
