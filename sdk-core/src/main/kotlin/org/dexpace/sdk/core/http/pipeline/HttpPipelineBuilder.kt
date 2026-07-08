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
 *
 * @constructor Starts a fresh, step-less builder whose terminal transport is [httpClient]. When
 *   [httpClient] happens to be an [HttpPipeline], this **nests** it as an opaque transport: the
 *   steps added to the outer builder run once, *outside* the nested pipeline's own
 *   redirect / retry / auth loops. That is rarely the intent — to instead **flatten** an existing
 *   pipeline (copy its steps into this builder) use the secondary `HttpPipelineBuilder(HttpPipeline)`
 *   constructor or [from]. Overload resolution picks the flattening constructor for a
 *   statically-typed [HttpPipeline] argument, so `HttpPipelineBuilder(pipeline)` flattens; reach
 *   for this transport constructor only when you genuinely want to nest a pipeline as a transport.
 */
public class HttpPipelineBuilder(private val httpClient: HttpClient) {
    private val steps: StagedSteps<HttpStep> = StagedSteps(stageOf = HttpStep::stage)

    /**
     * Seeds a new builder from [pipeline] by **flattening** it: [pipeline]'s steps become this
     * builder's steps and its transport becomes this builder's terminal transport (identical to
     * [from]). Prefer this over the generic [HttpClient] transport constructor when the argument
     * is an [HttpPipeline]; that constructor would instead **nest** the pipeline (see the primary
     * `@constructor` KDoc for the nesting hazard). Because [HttpPipeline] is more specific than
     * [HttpClient], `HttpPipelineBuilder(pipeline)` resolves to this flattening constructor.
     */
    public constructor(pipeline: HttpPipeline) : this(pipeline.httpClient) {
        steps.reload(pipeline.steps)
    }

    /** Append [step] at the tail of its stage's deque (runs after steps already there). */
    public fun append(step: HttpStep): HttpPipelineBuilder = apply { steps.append(step) }

    /** Prepend [step] at the head of its stage's deque (runs before steps already there). */
    public fun prepend(step: HttpStep): HttpPipelineBuilder = apply { steps.prepend(step) }

    /** Appends every step in [batch] via [append], preserving iteration order. */
    public fun appendAll(batch: Iterable<HttpStep>): HttpPipelineBuilder = apply { batch.forEach(::append) }

    /**
     * Prepends every step in [batch] via [prepend], preserving iteration order. Because each
     * call uses [prepend], the final relative ordering inside each stage is the reverse of
     * [batch] — the last item ends up at the head.
     */
    public fun prependAll(batch: Iterable<HttpStep>): HttpPipelineBuilder = apply { batch.forEach(::prepend) }

    /**
     * Insert [step] immediately after the first instance of [T] in the pipeline.
     *
     * Placement is **within [T]'s stage only**: [step] must declare the same [Stage] as the
     * matched anchor. A cross-stage insert throws [IllegalArgumentException] rather than
     * silently relocating [step] to wherever its own stage falls (steps are re-bucketed by
     * stage on every edit). Route a different-stage step with [append] / [prepend] instead.
     *
     * @throws IllegalStateException if [step] would install a *distinct* second step onto an
     *   already-occupied pillar stage — i.e. [T] is itself the pillar occupant and [step] is a
     *   different step declaring that same pillar stage. A pillar admits exactly one step, so the
     *   re-bucket after the insert rejects the duplicate. Use [replace] to swap a pillar's occupant.
     */
    public inline fun <reified T : HttpStep> insertAfter(step: HttpStep): HttpPipelineBuilder =
        insertAfter(T::class.java, step)

    /**
     * Insert [step] immediately before the first instance of [T] in the pipeline. Same
     * within-stage-only constraint as [insertAfter] — see its KDoc.
     *
     * @throws IllegalStateException if [step] would install a *distinct* second step onto an
     *   already-occupied pillar stage (see [insertAfter]); use [replace] to swap a pillar instead.
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
        steps.requirePillarsVacant(additions)
        return appendAll(additions)
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
        /**
         * Returns a new builder seeded with [pipeline]'s steps and client — i.e. **flattens**
         * [pipeline] into a fresh builder. Delegates to the flattening
         * `HttpPipelineBuilder(HttpPipeline)` constructor.
         */
        @JvmStatic
        public fun from(pipeline: HttpPipeline): HttpPipelineBuilder = HttpPipelineBuilder(pipeline)
    }
}
