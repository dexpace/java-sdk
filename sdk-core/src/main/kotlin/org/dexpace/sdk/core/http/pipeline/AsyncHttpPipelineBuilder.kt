/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.pipeline.steps.AsyncThrowOnHttpErrorStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultAsyncInstrumentationStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultAsyncRetryStep
import java.util.concurrent.ScheduledExecutorService

/**
 * Async counterpart of [HttpPipelineBuilder]. Mirrors the same API verbatim but typed over
 * [AsyncHttpStep] / [AsyncHttpClient]. The step-storage and surgical-edit policy lives in the
 * shared [StagedSteps] helper — including exclusive pillar occupancy: appending or prepending a
 * distinct second step to an already-occupied pillar throws [IllegalStateException]; use [replace]
 * to swap it.
 *
 * @constructor Starts a fresh, step-less builder whose terminal transport is [httpClient]. When
 *   [httpClient] happens to be an [AsyncHttpPipeline], this **nests** it as an opaque transport:
 *   the steps added to the outer builder run once, *outside* the nested pipeline's own
 *   retry / auth loops. That is rarely the intent — to instead **flatten** an existing pipeline
 *   (copy its steps into this builder) use the secondary `AsyncHttpPipelineBuilder(AsyncHttpPipeline)`
 *   constructor or [from]. Overload resolution picks the flattening constructor for a
 *   statically-typed [AsyncHttpPipeline] argument, so `AsyncHttpPipelineBuilder(pipeline)` flattens;
 *   reach for this transport constructor only when you genuinely want to nest a pipeline as a
 *   transport.
 */
public class AsyncHttpPipelineBuilder(private val httpClient: AsyncHttpClient) {
    private val steps: StagedSteps<AsyncHttpStep> = StagedSteps(stageOf = AsyncHttpStep::stage)

    /**
     * Seeds a new builder from [pipeline] by **flattening** it: [pipeline]'s steps become this
     * builder's steps and its transport becomes this builder's terminal transport (identical to
     * [from]). Prefer this over the generic [AsyncHttpClient] transport constructor whenever the
     * argument is an [AsyncHttpPipeline] — the transport constructor would instead **nest** the
     * pipeline as an opaque transport, running the outer steps outside the inner pipeline's
     * retry / auth loops. Because [AsyncHttpPipeline] is more specific than [AsyncHttpClient],
     * `AsyncHttpPipelineBuilder(pipeline)` resolves to this flattening constructor.
     */
    public constructor(pipeline: AsyncHttpPipeline) : this(pipeline.httpClient) {
        steps.reload(pipeline.steps)
    }

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
     *
     * @throws IllegalStateException if [step] would install a *distinct* second step onto an
     *   already-occupied pillar stage — i.e. [T] is itself the pillar occupant and [step] is a
     *   different step declaring that same pillar stage. A pillar admits exactly one step, so the
     *   re-bucket after the insert rejects the duplicate. Use [replace] to swap a pillar's occupant.
     */
    public inline fun <reified T : AsyncHttpStep> insertAfter(step: AsyncHttpStep): AsyncHttpPipelineBuilder =
        insertAfter(T::class.java, step)

    /**
     * Insert [step] immediately before the first instance of [T] in the pipeline. Same
     * within-stage-only constraint as [insertAfter] — see its KDoc.
     *
     * @throws IllegalStateException if [step] would install a *distinct* second step onto an
     *   already-occupied pillar stage (see [insertAfter]); use [replace] to swap a pillar instead.
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

    /**
     * Appends an [AsyncThrowOnHttpErrorStep] so a 4xx / 5xx completion is surfaced as the matching
     * typed [org.dexpace.sdk.core.http.response.exception.HttpException] (via an exceptional future
     * completion) instead of a plain [org.dexpace.sdk.core.http.response.Response]. Mirrors
     * [HttpPipelineBuilder.throwOnHttpError]; the step occupies the outermost [Stage.PRE_REDIRECT]
     * slot so it only maps the terminal response.
     */
    public fun throwOnHttpError(): AsyncHttpPipelineBuilder = append(AsyncThrowOnHttpErrorStep())

    /**
     * Appends the async standard resilience defaults: [DefaultAsyncRetryStep] ([Stage.RETRY]) and
     * [DefaultAsyncInstrumentationStep] ([Stage.LOGGING]).
     *
     * ## The async pipeline does NOT follow redirects
     * There is **no async redirect step in the SDK**, so — unlike the synchronous
     * [HttpPipelineBuilder.appendStandardResilience], which installs a `DefaultRedirectStep` — this
     * preset (and the async pipeline as a whole) follows **no** HTTP redirects at any layer. Both
     * reference transports also default to `followRedirects = false`, so a 3xx surfaces to the
     * caller verbatim rather than being followed. If you need redirects followed you must either
     * enable redirect following on the transport itself (e.g. the OkHttp / JDK-HttpClient
     * `followRedirects` option) or use the synchronous [HttpPipeline], which ships a redirect step.
     * Installing a custom async [Stage.REDIRECT] step is also an option.
     *
     * [DefaultAsyncRetryStep] requires a [ScheduledExecutorService] on which to schedule its
     * (non-blocking) backoff delays, so — unlike the synchronous no-arg preset — this method takes
     * [scheduler]. The scheduler is the caller's to own and shut down; the SDK never closes it.
     *
     * Like its synchronous mirror, this is a preset for the **empty** [Stage.RETRY] / [Stage.LOGGING]
     * slots, not a composable overlay on top of pillars you have already installed. The supported
     * order is: call this first, then customize an individual pillar with [replace].
     *
     * @throws IllegalStateException if the [Stage.RETRY] or [Stage.LOGGING] pillar is already
     *   occupied on this builder — including calling this method twice, or invoking it on a builder
     *   seeded via [from] from a pipeline that already carries them. The check runs before any step
     *   is installed, so a rejected call leaves the builder completely unchanged (all-or-nothing).
     *   Swap a single pillar with [replace] instead.
     */
    public fun appendStandardResilience(scheduler: ScheduledExecutorService): AsyncHttpPipelineBuilder {
        val additions: List<AsyncHttpStep> =
            listOf(
                DefaultAsyncRetryStep(scheduler),
                DefaultAsyncInstrumentationStep(),
            )
        steps.requirePillarsVacant(additions)
        return appendAll(additions)
    }

    /** Builds an immutable [AsyncHttpPipeline]. */
    public fun build(): AsyncHttpPipeline {
        val ordered = steps.flatten()
        return AsyncHttpPipeline(httpClient, ordered.toTypedArray())
    }

    public companion object {
        /**
         * Returns a new builder seeded with [pipeline]'s steps and client — i.e. **flattens**
         * [pipeline] into a fresh builder. Delegates to the flattening
         * `AsyncHttpPipelineBuilder(AsyncHttpPipeline)` constructor.
         */
        @JvmStatic
        public fun from(pipeline: AsyncHttpPipeline): AsyncHttpPipelineBuilder = AsyncHttpPipelineBuilder(pipeline)
    }
}
