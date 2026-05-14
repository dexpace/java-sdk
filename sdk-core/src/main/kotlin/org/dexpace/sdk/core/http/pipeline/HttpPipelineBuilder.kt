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
    @PublishedApi
    internal val steps: StagedSteps<HttpStep> =
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

    /** Insert [step] immediately after the first instance of [T] in the pipeline. */
    public inline fun <reified T : HttpStep> insertAfter(step: HttpStep): HttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<HttpStep>(flat.size + 1).apply {
                addAll(flat.subList(0, idx + 1))
                add(step)
                addAll(flat.subList(idx + 1, flat.size))
            }
        }

    /** Insert [step] immediately before the first instance of [T] in the pipeline. */
    public inline fun <reified T : HttpStep> insertBefore(step: HttpStep): HttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<HttpStep>(flat.size + 1).apply {
                addAll(flat.subList(0, idx))
                add(step)
                addAll(flat.subList(idx, flat.size))
            }
        }

    /** Replace the first instance of [T] with [step]. */
    public inline fun <reified T : HttpStep> replace(step: HttpStep): HttpPipelineBuilder =
        spliceAt<T>(step) { idx, flat ->
            ArrayList<HttpStep>(flat.size).apply {
                addAll(flat)
                set(idx, step)
            }
        }

    /** Remove every instance of [T] from the pipeline. No-op if none exist. */
    public inline fun <reified T : HttpStep> remove(): HttpPipelineBuilder {
        steps.reload(steps.flatten().filter { it !is T })
        return this
    }

    /**
     * Inline plumbing for the surgical edits: locate the first instance of `T`, invoke
     * [transform] to produce a new step list, and rebuild from it. Throws if no `T` exists —
     * callers prefer fail-fast over silent no-op for "edit the X step" intent.
     */
    @PublishedApi
    internal inline fun <reified T : HttpStep> spliceAt(
        @Suppress("UNUSED_PARAMETER") step: HttpStep,
        transform: (Int, List<HttpStep>) -> List<HttpStep>,
    ): HttpPipelineBuilder {
        val flat = steps.flatten()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        steps.reload(transform(idx, flat))
        return this
    }

    /**
     * Builds an immutable [HttpPipeline] in stage order. [Stage.SEND] is reserved for the
     * transport and is skipped.
     *
     * `arrayOfNulls<HttpStep>` then fill — Kotlin's `List.toTypedArray<T>()` is erased to
     * `Array<Any?>` at runtime which fails the `Array<HttpStep>` cast.
     */
    public fun build(): HttpPipeline {
        val ordered = steps.flatten()
        val array = arrayOfNulls<HttpStep>(ordered.size)
        for ((i, s) in ordered.withIndex()) array[i] = s
        @Suppress("UNCHECKED_CAST")
        return HttpPipeline(httpClient, array as Array<HttpStep>)
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
