/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.pipeline.steps.DefaultInstrumentationStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRedirectStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep
import org.dexpace.sdk.core.http.pipeline.steps.ThrowOnHttpErrorStep

/**
 * Mutable builder for [HttpPipeline]. Steps are organised by their declared [Stage]:
 * pillar stages hold a single instance; non-pillar stages hold an ordered deque.
 *
 * Primary API ([append] / [prepend]) routes a step by its declared stage. Surgical API
 * ([insertAfter] / [insertBefore] / [replace] / [remove]) operates relative to existing
 * step types, useful for tweaking an already-built pipeline via [from].
 *
 * Thread-safety: not thread-safe. Construct on one thread, then call [build] — the resulting
 * [HttpPipeline] is immutable.
 *
 * Pillar occupancy: a pillar stage admits exactly one step. Appending or prepending a *distinct*
 * second step to an already-occupied pillar throws [IllegalStateException]; use [replace] to swap
 * the existing step. Re-installing the same instance is idempotent.
 */
public class HttpPipelineBuilder(private val httpClient: HttpClient) {
    private val steps: StagedSteps<HttpStep> = StagedSteps(stageOf = HttpStep::stage)

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
     * Appends a [ThrowOnHttpErrorStep] so a 4xx / 5xx response is surfaced as the matching typed
     * [org.dexpace.sdk.core.http.response.exception.HttpException] instead of a plain
     * [org.dexpace.sdk.core.http.response.Response]. The step occupies the outermost
     * [Stage.PRE_REDIRECT] slot, so it only maps the terminal response (after redirect following
     * and retry exhaustion). See [ThrowOnHttpErrorStep].
     */
    public fun throwOnHttpError(): HttpPipelineBuilder = append(ThrowOnHttpErrorStep())

    /**
     * Appends the SDK's standard resilience pillars with their no-arg defaults:
     * [DefaultRedirectStep] ([Stage.REDIRECT]), [DefaultRetryStep] ([Stage.RETRY]), and
     * [DefaultInstrumentationStep] ([Stage.LOGGING]). Each lands in its own pillar slot; their
     * relative run order is fixed by [Stage] — [Stage.REDIRECT], then [Stage.RETRY], then
     * [Stage.LOGGING] — no matter where in the builder chain this call sits.
     *
     * This is a preset for the **empty** REDIRECT / RETRY / LOGGING slots, not a composable overlay
     * on top of pillars you have already installed. The supported order is: call this first — on a
     * fresh builder, or before configuring any of those three pillars by hand — then customize an
     * individual pillar with [replace]. Combine with [throwOnHttpError] for turnkey error mapping on
     * top of the resilience stack.
     *
     * @throws IllegalStateException if any of the [Stage.REDIRECT], [Stage.RETRY], or
     *   [Stage.LOGGING] pillars is already occupied on this builder — including calling this method
     *   twice, or invoking it on a builder seeded via [from] from a pipeline that already carries
     *   them. The check runs before any step is installed, so a rejected call leaves the builder
     *   completely unchanged (all-or-nothing). Swap a single pillar with [replace] instead.
     */
    public fun appendStandardResilience(): HttpPipelineBuilder {
        val additions: List<HttpStep> =
            listOf(
                DefaultRedirectStep(),
                DefaultRetryStep(),
                DefaultInstrumentationStep(),
            )
        requireStandardPillarsAvailable(additions)
        return appendAll(additions)
    }

    /**
     * Precondition for [appendStandardResilience]: none of the pillar stages [additions] will fill
     * may already be occupied. Validating up front turns what would otherwise be a
     * partial-mutation-then-throw (the first pillar installed before a later [append] rejects the
     * conflict) into an atomic all-or-nothing check.
     */
    private fun requireStandardPillarsAvailable(additions: List<HttpStep>) {
        val occupied = additions.map { it.stage }.filter { it.isPillar && steps.pillarAt(it) != null }
        check(occupied.isEmpty()) {
            "appendStandardResilience() installs the SDK's resilience pillars into empty slots only, " +
                "but ${occupied.joinToString(", ")} ${if (occupied.size == 1) "is" else "are"} already " +
                "configured on this builder. Call appendStandardResilience() first (on a fresh builder), " +
                "then customize an individual pillar via replace<T>()."
        }
    }

    /**
     * Builds an immutable [HttpPipeline] in stage order. [Stage.SEND] is reserved for the
     * transport and is skipped.
     */
    public fun build(): HttpPipeline {
        val ordered = steps.flatten()
        return HttpPipeline(httpClient, ordered.toTypedArray())
    }

    public companion object {
        /** Returns a new builder seeded with [pipeline]'s steps and client. */
        @JvmStatic
        public fun from(pipeline: HttpPipeline): HttpPipelineBuilder =
            HttpPipelineBuilder(pipeline.httpClient)
                .also { it.steps.reload(pipeline.steps) }
    }
}
