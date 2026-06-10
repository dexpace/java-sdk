/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [StagedSteps] internal bookkeeping, focusing on edge cases not covered
 * by [HttpPipelineTest] (which exercises StagedSteps indirectly through the builder).
 *
 * [StagedSteps] keeps its `perStage` / `pillars` storage private; tests inspect it through the
 * read-only [StagedSteps.pillarAt] / [StagedSteps.nonPillarAt] snapshots and the
 * [StagedSteps.flatten] view.
 */
class StagedStepsTest {
    /**
     * A minimal step-like type for StagedSteps unit testing; not wired to the real
     * pipeline machinery — we only need `stageOf` to compile.
     */
    private class FakeStep(val stage: Stage, val tag: String)

    private fun stagedSteps(
        onPillarReplaced: (Stage, FakeStep, FakeStep) -> Unit = { _, _, _ -> },
    ): StagedSteps<FakeStep> = StagedSteps(stageOf = { it.stage }, onPillarReplaced = onPillarReplaced)

    // -------- reload: dup-pillar warning --------

    @Test
    fun `reload with two pillar steps for the same stage fires onPillarReplaced`() {
        val replacements = mutableListOf<Triple<Stage, FakeStep, FakeStep>>()
        val staged =
            stagedSteps { stage, old, new ->
                replacements.add(Triple(stage, old, new))
            }

        val firstRetry = FakeStep(Stage.RETRY, "retry-A")
        val secondRetry = FakeStep(Stage.RETRY, "retry-B")

        staged.reload(listOf(firstRetry, secondRetry))

        assertEquals(1, replacements.size, "onPillarReplaced should fire exactly once")
        val (stage, old, new) = replacements[0]
        assertEquals(Stage.RETRY, stage)
        assertEquals("retry-A", old.tag)
        assertEquals("retry-B", new.tag)

        // The second (newer) pillar wins.
        assertEquals("retry-B", staged.pillarAt(Stage.RETRY)?.tag)
    }

    @Test
    fun `reload with single pillar step does not fire onPillarReplaced`() {
        val replacements = mutableListOf<Triple<Stage, FakeStep, FakeStep>>()
        val staged =
            stagedSteps { stage, old, new ->
                replacements.add(Triple(stage, old, new))
            }

        staged.reload(listOf(FakeStep(Stage.RETRY, "retry-A")))

        assertTrue(replacements.isEmpty(), "onPillarReplaced must not fire on first install")
    }

    @Test
    fun `reload same pillar instance twice does not fire onPillarReplaced`() {
        val replacements = mutableListOf<Triple<Stage, FakeStep, FakeStep>>()
        val staged =
            stagedSteps { stage, old, new ->
                replacements.add(Triple(stage, old, new))
            }

        val retry = FakeStep(Stage.RETRY, "retry")
        staged.reload(listOf(retry, retry))

        assertTrue(replacements.isEmpty(), "same-instance re-install must not fire onPillarReplaced")
        assertEquals("retry", staged.pillarAt(Stage.RETRY)?.tag)
    }

    // -------- reload: general contract --------

    @Test
    fun `reload clears previous state`() {
        val staged = stagedSteps()

        staged.append(FakeStep(Stage.PRE_AUTH, "old"))
        staged.reload(listOf(FakeStep(Stage.POST_AUTH, "new")))

        assertTrue(staged.nonPillarAt(Stage.PRE_AUTH).isEmpty())
        assertEquals(1, staged.nonPillarAt(Stage.POST_AUTH).size)
    }

    @Test
    fun `reload with empty list results in empty storage`() {
        val staged = stagedSteps()
        staged.append(FakeStep(Stage.PRE_AUTH, "step"))
        staged.reload(emptyList())

        assertTrue(staged.flatten().isEmpty())
        assertTrue(staged.pillarAt(Stage.PRE_AUTH) == null)
    }

    // -------- append / prepend pillar routing --------

    @Test
    fun `append two distinct pillar steps fires onPillarReplaced`() {
        val replacements = mutableListOf<Triple<Stage, FakeStep, FakeStep>>()
        val staged =
            stagedSteps { stage, old, new ->
                replacements.add(Triple(stage, old, new))
            }

        val a = FakeStep(Stage.AUTH, "auth-A")
        val b = FakeStep(Stage.AUTH, "auth-B")
        staged.append(a)
        staged.append(b)

        assertEquals(1, replacements.size)
        assertEquals("auth-A", replacements[0].second.tag)
        assertEquals("auth-B", replacements[0].third.tag)
    }

    // -------- nonPillarAt / pillarAt return defensive copies --------

    @Test
    fun `nonPillarAt returns a snapshot copy, not the live deque`() {
        val staged = stagedSteps()
        staged.append(FakeStep(Stage.PRE_AUTH, "a"))

        val snapshot = staged.nonPillarAt(Stage.PRE_AUTH)
        // The returned list is a copy; mutating it (via cast) must not corrupt internal state.
        @Suppress("UNCHECKED_CAST")
        (snapshot as MutableList<FakeStep>).clear()

        assertEquals(1, staged.nonPillarAt(Stage.PRE_AUTH).size, "internal deque must be untouched")
    }

    // -------- surgical edits: same-stage success --------

    @Test
    fun `insertRelativeToFirst after places step right after the anchor within the same stage`() {
        val staged = stagedSteps()
        val anchor = FakeStep(Stage.PRE_AUTH, "anchor")
        val inserted = FakeStep(Stage.PRE_AUTH, "inserted")
        staged.append(anchor)

        staged.insertRelativeToFirst(FakeStep::class.java, inserted, InsertPosition.AFTER)

        assertEquals(listOf("anchor", "inserted"), staged.flatten().map { it.tag })
    }

    @Test
    fun `replaceFirst swaps the first matching step within the same stage`() {
        val staged = stagedSteps()
        val original = FakeStep(Stage.PRE_AUTH, "original")
        val replacement = FakeStep(Stage.PRE_AUTH, "replacement")
        staged.append(original)

        staged.replaceFirst(FakeStep::class.java, replacement)

        assertEquals(listOf("replacement"), staged.flatten().map { it.tag })
    }

    // -------- surgical edits: cross-stage rejection (A-5) --------

    @Test
    fun `insertRelativeToFirst rejects a step whose stage differs from the anchor`() {
        val staged = stagedSteps()
        staged.append(FakeStep(Stage.PRE_AUTH, "anchor"))

        val crossStage = FakeStep(Stage.POST_AUTH, "cross")
        val ex =
            assertFailsWith<IllegalArgumentException> {
                staged.insertRelativeToFirst(FakeStep::class.java, crossStage, InsertPosition.AFTER)
            }
        assertTrue(ex.message!!.contains("PRE_AUTH"), "message names anchor stage: ${ex.message}")
        assertTrue(ex.message!!.contains("POST_AUTH"), "message names step stage: ${ex.message}")
    }

    @Test
    fun `replaceFirst rejects a replacement whose stage differs from the replaced step`() {
        val staged = stagedSteps()
        staged.append(FakeStep(Stage.PRE_AUTH, "original"))

        val crossStage = FakeStep(Stage.POST_AUTH, "cross")
        assertFailsWith<IllegalArgumentException> {
            staged.replaceFirst(FakeStep::class.java, crossStage)
        }
    }

    @Test
    fun `insertRelativeToFirst throws when no anchor of the requested type exists`() {
        val staged = stagedSteps()
        val ex =
            assertFailsWith<IllegalArgumentException> {
                staged.insertRelativeToFirst(
                    FakeStep::class.java,
                    FakeStep(Stage.PRE_AUTH, "x"),
                    InsertPosition.BEFORE,
                )
            }
        assertTrue(ex.message!!.contains("No"), "message: ${ex.message}")
    }
}
