package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.slf4j.LoggerFactory

/**
 * Mutable builder for [HttpPipeline]. Steps are organised by their declared [Stage]:
 * pillar stages hold a single instance (with replace-emits-warning semantics); non-pillar
 * stages hold an ordered deque.
 *
 * Primary API ([append] / [prepend]) routes a step by its declared stage. Surgical API
 * ([insertAfter] / [insertBefore] / [replace] / [remove]) operates relative to existing
 * step types, useful for tweaking an already-built pipeline via [from].
 *
 * Thread-safety: not thread-safe. Construct on one thread, then call [build] — the
 * resulting [HttpPipeline] is immutable.
 *
 * Logging: pillar replacement emits a `pipeline.pillar.replaced` warning via SLF4J. The
 * structured `ClientLogger` facade is deferred to a later phase; for now the warning is a
 * plain log line.
 */
class HttpPipelineBuilder(private val httpClient: HttpClient) {

    private val perStage: MutableMap<Stage, ArrayDeque<HttpStep>> = mutableMapOf()
    private val pillars: MutableMap<Stage, HttpStep> = mutableMapOf()

    /** Append [step] at the tail of its stage's deque (runs after steps already there). */
    fun append(step: HttpStep): HttpPipelineBuilder = apply {
        if (step.stage.isPillar) installPillar(step)
        else perStage.getOrPut(step.stage) { ArrayDeque() }.addLast(step)
    }

    /** Prepend [step] at the head of its stage's deque (runs before steps already there). */
    fun prepend(step: HttpStep): HttpPipelineBuilder = apply {
        if (step.stage.isPillar) installPillar(step)
        else perStage.getOrPut(step.stage) { ArrayDeque() }.addFirst(step)
    }

    /** Insert [step] immediately after the first instance of [T] in the pipeline. */
    inline fun <reified T : HttpStep> insertAfter(step: HttpStep): HttpPipelineBuilder {
        val flat = flattenedView()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        return reinsertAt(idx + 1, step, flat)
    }

    /** Insert [step] immediately before the first instance of [T] in the pipeline. */
    inline fun <reified T : HttpStep> insertBefore(step: HttpStep): HttpPipelineBuilder {
        val flat = flattenedView()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        return reinsertAt(idx, step, flat)
    }

    /** Replace the first instance of [T] with [step]. */
    inline fun <reified T : HttpStep> replace(step: HttpStep): HttpPipelineBuilder {
        val flat = flattenedView()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        val replaced = ArrayList<HttpStep>(flat.size)
        for (i in flat.indices) replaced.add(if (i == idx) step else flat[i])
        return rebuildFrom(replaced)
    }

    /** Remove every instance of [T] from the pipeline. No-op if none exist. */
    inline fun <reified T : HttpStep> remove(): HttpPipelineBuilder {
        val flat = flattenedView()
        val filtered = ArrayList<HttpStep>(flat.size)
        for (s in flat) if (s !is T) filtered.add(s)
        return rebuildFrom(filtered)
    }

    /**
     * Builds an immutable [HttpPipeline]. Iterates [Stage.entries] in declaration order,
     * emitting pillars and non-pillar deques; [Stage.SEND] is the terminal slot reserved
     * for [HttpClient] and is skipped.
     */
    fun build(): HttpPipeline {
        val stages = STAGES_CACHED
        // Two-pass to size the array exactly — avoids list-to-array conversion overhead.
        var count = 0
        for (stage in stages) {
            if (stage == Stage.SEND) continue
            count += if (stage.isPillar) {
                if (pillars[stage] != null) 1 else 0
            } else {
                perStage[stage]?.size ?: 0
            }
        }
        val out = arrayOfNulls<HttpStep>(count)
        var idx = 0
        for (stage in stages) {
            if (stage == Stage.SEND) continue
            if (stage.isPillar) {
                val pillar = pillars[stage]
                if (pillar != null) out[idx++] = pillar
            } else {
                val deque = perStage[stage]
                if (deque != null) for (s in deque) out[idx++] = s
            }
        }
        @Suppress("UNCHECKED_CAST")
        return HttpPipeline(httpClient, out as Array<HttpStep>)
    }

    @PublishedApi
    internal fun flattenedView(): List<HttpStep> {
        val stages = STAGES_CACHED
        val out = ArrayList<HttpStep>()
        for (stage in stages) {
            if (stage == Stage.SEND) continue
            if (stage.isPillar) {
                val pillar = pillars[stage]
                if (pillar != null) out.add(pillar)
            } else {
                val deque = perStage[stage]
                if (deque != null) out.addAll(deque)
            }
        }
        return out
    }

    @PublishedApi
    internal fun reinsertAt(index: Int, step: HttpStep, flat: List<HttpStep>): HttpPipelineBuilder {
        val rebuilt = ArrayList<HttpStep>(flat.size + 1)
        for (i in 0 until index) rebuilt.add(flat[i])
        rebuilt.add(step)
        for (i in index until flat.size) rebuilt.add(flat[i])
        return rebuildFrom(rebuilt)
    }

    @PublishedApi
    internal fun rebuildFrom(steps: List<HttpStep>): HttpPipelineBuilder {
        perStage.clear()
        pillars.clear()
        for (s in steps) {
            if (s.stage.isPillar) pillars[s.stage] = s
            else perStage.getOrPut(s.stage) { ArrayDeque() }.addLast(s)
        }
        return this
    }

    private fun installPillar(step: HttpStep) {
        check(step.stage != Stage.SEND) { "SEND is the terminal HttpClient — not a step slot" }
        val existing = pillars[step.stage]
        if (existing != null && existing !== step) {
            LOG.warn(
                "pipeline.pillar.replaced stage={} previous={} replacement={}",
                step.stage,
                existing::class.simpleName,
                step::class.simpleName,
            )
        }
        pillars[step.stage] = step
    }

    companion object {
        @PublishedApi
        internal val LOG = LoggerFactory.getLogger(HttpPipelineBuilder::class.java)

        /** Cached enum entries — `Stage.entries` does not clone per access, but reading once is still tighter. */
        @PublishedApi
        internal val STAGES_CACHED: List<Stage> = Stage.entries

        /**
         * Returns a new builder seeded with [pipeline]'s steps and client. Used for
         * surgical edits to a pre-built pipeline via [insertAfter] / [replace] / etc.
         */
        @JvmStatic
        fun from(pipeline: HttpPipeline): HttpPipelineBuilder {
            val builder = HttpPipelineBuilder(pipeline.httpClient)
            for (step in pipeline.stepArray) {
                if (step.stage.isPillar) builder.pillars[step.stage] = step
                else builder.perStage.getOrPut(step.stage) { ArrayDeque() }.addLast(step)
            }
            return builder
        }
    }
}
