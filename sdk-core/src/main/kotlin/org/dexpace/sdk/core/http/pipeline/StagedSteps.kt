package org.dexpace.sdk.core.http.pipeline

/**
 * Stage-keyed step storage shared by [HttpPipelineBuilder] and [AsyncHttpPipelineBuilder].
 * Each pipeline variant carries its own concrete step type ([HttpStep] / [AsyncHttpStep]) so the
 * builder is generic over `S`; the staging policy — pillar slots hold one, non-pillar stages
 * hold an ordered deque — is identical for both.
 *
 * Extracting this here removes the duplicated map management, append/prepend/insert/replace/remove
 * bookkeeping, and the flatten-for-build pass that the two builders would otherwise repeat
 * verbatim.
 *
 * Internal: only the builders construct and mutate instances. The `getStage` lambda is the
 * type-erased view onto each step's stage; we don't require a base interface because the two
 * step types are siblings with no shared supertype beyond `Any`.
 */
@PublishedApi
internal class StagedSteps<S : Any>(
    /** Reads the stage off a step instance. */
    private val stageOf: (S) -> Stage,
    /**
     * Called when an `append`/`prepend` of a pillar step would replace an existing one with
     * a different instance. The builder uses this to log a warning through its own logger
     * (SLF4J directly today; could become `ClientLogger` later) without baking logging into
     * this generic helper.
     */
    private val onPillarReplaced: (Stage, S, S) -> Unit = { _, _, _ -> },
) {
    /** Non-pillar stages: ordered deque per stage. */
    val perStage: MutableMap<Stage, ArrayDeque<S>> = mutableMapOf()

    /** Pillar stages: at most one step per stage. */
    val pillars: MutableMap<Stage, S> = mutableMapOf()

    /** Append to the tail of [step]'s stage deque (or install the pillar). */
    fun append(step: S) {
        val stage = stageOf(step)
        if (stage.isPillar) installPillar(step, stage)
        else perStage.getOrPut(stage) { ArrayDeque() }.addLast(step)
    }

    /** Prepend to the head of [step]'s stage deque (or install the pillar). */
    fun prepend(step: S) {
        val stage = stageOf(step)
        if (stage.isPillar) installPillar(step, stage)
        else perStage.getOrPut(stage) { ArrayDeque() }.addFirst(step)
    }

    /**
     * Walks every stage in declaration order, emitting pillar singletons and non-pillar deques.
     * [Stage.SEND] is reserved for the terminal client and is skipped.
     */
    fun flatten(): List<S> {
        val out = ArrayList<S>()
        for (stage in Stage.entries) {
            if (stage == Stage.SEND) continue
            if (stage.isPillar) pillars[stage]?.let(out::add)
            else perStage[stage]?.let(out::addAll)
        }
        return out
    }

    /** Replaces the contents of this storage with [steps], preserving order. */
    fun reload(steps: List<S>) {
        perStage.clear()
        pillars.clear()
        for (s in steps) {
            val stage = stageOf(s)
            if (stage.isPillar) installPillar(s, stage)
            else perStage.getOrPut(stage) { ArrayDeque() }.addLast(s)
        }
    }

    private fun installPillar(step: S, stage: Stage) {
        check(stage != Stage.SEND) { "SEND is the terminal client — not a step slot" }
        val existing = pillars[stage]
        if (existing != null && existing !== step) onPillarReplaced(stage, existing, step)
        pillars[stage] = step
    }
}
