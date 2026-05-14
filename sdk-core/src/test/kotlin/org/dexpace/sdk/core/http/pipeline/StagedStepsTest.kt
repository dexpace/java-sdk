package org.dexpace.sdk.core.http.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [StagedSteps] internal bookkeeping, focusing on edge cases not covered
 * by [HttpPipelineTest] (which exercises StagedSteps indirectly through the builder).
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
        assertEquals("retry-B", staged.pillars[Stage.RETRY]?.tag)
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
        assertEquals("retry", staged.pillars[Stage.RETRY]?.tag)
    }

    // -------- reload: general contract --------

    @Test
    fun `reload clears previous state`() {
        val staged = stagedSteps()

        staged.append(FakeStep(Stage.PRE_AUTH, "old"))
        staged.reload(listOf(FakeStep(Stage.POST_AUTH, "new")))

        assertTrue(staged.perStage[Stage.PRE_AUTH] == null || staged.perStage[Stage.PRE_AUTH]!!.isEmpty())
        assertEquals(1, staged.perStage[Stage.POST_AUTH]?.size)
    }

    @Test
    fun `reload with empty list results in empty storage`() {
        val staged = stagedSteps()
        staged.append(FakeStep(Stage.PRE_AUTH, "step"))
        staged.reload(emptyList())

        assertTrue(staged.perStage.all { it.value.isEmpty() })
        assertTrue(staged.pillars.isEmpty())
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
}
