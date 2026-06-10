/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
 *
 * This type is plain `internal` (not `@PublishedApi`): the builders' surgical-edit helpers go
 * through the non-inline [insertRelativeToFirst] / [replaceFirst] / [removeMatching] entry
 * points, so `StagedSteps` and its mutable `perStage` / `pillars` storage never escape into the
 * published binary API.
 */
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
    private val perStage: MutableMap<Stage, ArrayDeque<S>> = mutableMapOf()

    /** Pillar stages: at most one step per stage. */
    private val pillars: MutableMap<Stage, S> = mutableMapOf()

    /** Append to the tail of [step]'s stage deque (or install the pillar). */
    fun append(step: S) {
        val stage = stageOf(step)
        if (stage.isPillar) {
            installPillar(step, stage)
        } else {
            perStage.getOrPut(stage) { ArrayDeque() }.addLast(step)
        }
    }

    /** Prepend to the head of [step]'s stage deque (or install the pillar). */
    fun prepend(step: S) {
        val stage = stageOf(step)
        if (stage.isPillar) {
            installPillar(step, stage)
        } else {
            perStage.getOrPut(stage) { ArrayDeque() }.addFirst(step)
        }
    }

    /**
     * Walks every stage in declaration order, emitting pillar singletons and non-pillar deques.
     * [Stage.SEND] is reserved for the terminal client and is skipped.
     */
    fun flatten(): List<S> {
        val out = ArrayList<S>()
        for (stage in Stage.entries) {
            if (stage == Stage.SEND) continue
            if (stage.isPillar) {
                pillars[stage]?.let(out::add)
            } else {
                perStage[stage]?.let(out::addAll)
            }
        }
        return out
    }

    /** Replaces the contents of this storage with [steps], preserving order. */
    fun reload(steps: List<S>) {
        perStage.clear()
        pillars.clear()
        for (s in steps) {
            val stage = stageOf(s)
            if (stage.isPillar) {
                installPillar(s, stage)
            } else {
                perStage.getOrPut(stage) { ArrayDeque() }.addLast(s)
            }
        }
    }

    /**
     * Insert [step] relative to the first existing step that is an instance of [anchorType],
     * then re-bucket every step by [Stage]. [position] selects whether [step] lands immediately
     * before or after the anchor.
     *
     * Because the storage re-buckets by `Stage` on every mutation, a flat index only survives
     * the round-trip when [step] shares the anchor's stage. To keep "insert immediately
     * before/after the anchor" honest rather than silently relocating [step] to wherever its
     * own stage falls, this rejects a cross-stage insert with [IllegalArgumentException].
     *
     * @throws IllegalArgumentException if no instance of [anchorType] exists, or if
     *   `stageOf(step)` differs from the matched anchor's stage.
     */
    fun insertRelativeToFirst(
        anchorType: Class<out S>,
        step: S,
        position: InsertPosition,
    ) {
        val flat = flatten()
        val idx = flat.indexOfFirst { anchorType.isInstance(it) }
        require(idx >= 0) { "No ${anchorType.simpleName} in pipeline" }
        val anchorStage = stageOf(flat[idx])
        val stepStage = stageOf(step)
        require(stepStage == anchorStage) {
            "Cannot ${position.verb} a $stepStage step relative to the first " +
                "${anchorType.simpleName} (stage $anchorStage): insertAfter/insertBefore place a " +
                "step within the anchor's stage only. Route it with append/prepend instead, or " +
                "give it the $anchorStage stage."
        }
        val out = ArrayList<S>(flat.size + 1)
        val at = if (position == InsertPosition.AFTER) idx + 1 else idx
        out.addAll(flat.subList(0, at))
        out.add(step)
        out.addAll(flat.subList(at, flat.size))
        reload(out)
    }

    /**
     * Replace the first existing step that is an instance of [anchorType] with [step], then
     * re-bucket by [Stage]. As with [insertRelativeToFirst], a flat index only survives the
     * re-bucket when [step] shares the replaced step's stage, so a cross-stage replacement is
     * rejected rather than silently relocating [step].
     *
     * @throws IllegalArgumentException if no instance of [anchorType] exists, or if
     *   `stageOf(step)` differs from the matched step's stage.
     */
    fun replaceFirst(
        anchorType: Class<out S>,
        step: S,
    ) {
        val flat = flatten()
        val idx = flat.indexOfFirst { anchorType.isInstance(it) }
        require(idx >= 0) { "No ${anchorType.simpleName} in pipeline" }
        val anchorStage = stageOf(flat[idx])
        val stepStage = stageOf(step)
        require(stepStage == anchorStage) {
            "Cannot replace the first ${anchorType.simpleName} (stage $anchorStage) with a " +
                "$stepStage step: replace swaps a step within its own stage only. Use " +
                "remove + append/prepend to move it across stages."
        }
        val out = ArrayList<S>(flat)
        out[idx] = step
        reload(out)
    }

    /** Removes every step that is an instance of [type], preserving the order of the rest. */
    fun removeMatching(type: Class<out S>) {
        reload(flatten().filterNot { type.isInstance(it) })
    }

    /** Snapshot of the current pillar at [stage], or `null` if none is installed (test seam). */
    fun pillarAt(stage: Stage): S? = pillars[stage]

    /** Snapshot copy of the non-pillar deque at [stage] (test seam; never the live deque). */
    fun nonPillarAt(stage: Stage): List<S> = ArrayList(perStage[stage] ?: emptyList())

    private fun installPillar(
        step: S,
        stage: Stage,
    ) {
        check(stage != Stage.SEND) { "SEND is the terminal client — not a step slot" }
        val existing = pillars[stage]
        if (existing != null && existing !== step) onPillarReplaced(stage, existing, step)
        pillars[stage] = step
    }
}

/** Side of an anchor that a surgical insert targets. */
internal enum class InsertPosition(internal val verb: String) {
    BEFORE("insertBefore"),
    AFTER("insertAfter"),
}
