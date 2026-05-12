package org.dexpace.sdk.core.pipeline.step

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [PipelineStepMetadataTrait]'s default property implementations. Like
 * [PipelineStepRetryConfigTraitTest], this locks in the public-contract defaults so
 * silent drift trips a test rather than escaping into production.
 */
class PipelineStepMetadataTraitTest {

    /** Overrides nothing — every property read hits the default getter. */
    private object AllDefaults : PipelineStepMetadataTrait

    @Test
    fun `default name is the placeholder string`() {
        assertEquals("Default name", AllDefaults.name)
    }

    @Test
    fun `default description is the placeholder string`() {
        assertEquals("Default description", AllDefaults.description)
    }

    @Test
    fun `default version is the placeholder string`() {
        assertEquals("Default version", AllDefaults.version)
    }

    @Test
    fun `default tags is the empty list`() {
        assertTrue(AllDefaults.tags.isEmpty())
    }

    @Test
    fun `trait implements PipelineStepConfigTrait`() {
        // Confirms the marker root inheritance — same rationale as for the retry trait test.
        val trait: PipelineStepConfigTrait = AllDefaults
        assertSame(AllDefaults, trait)
    }

    @Test
    fun `overriding name does not affect description, version, or tags`() {
        val custom = object : PipelineStepMetadataTrait {
            override val name: String = "custom"
        }
        assertEquals("custom", custom.name)
        assertEquals("Default description", custom.description)
        assertEquals("Default version", custom.version)
        assertTrue(custom.tags.isEmpty())
    }

    @Test
    fun `overriding tags returns the overridden list`() {
        val custom = object : PipelineStepMetadataTrait {
            override val tags: List<String> = listOf("a", "b")
        }
        assertEquals(listOf("a", "b"), custom.tags)
    }

    // ----- $DefaultImpls bridge: invoked by Java callers -----

    /**
     * Kotlin compiles default interface methods into `Trait$DefaultImpls` static methods
     * so older Java callers can dispatch them. Coverage sees these as separate from the
     * Kotlin-side getters; invoke reflectively to exercise the bridge path.
     */
    @Test
    fun `DefaultImpls bridge methods return the trait defaults`() {
        val cls = Class.forName(
            "org.dexpace.sdk.core.pipeline.step.PipelineStepMetadataTrait\$DefaultImpls",
        )
        val traitCls = PipelineStepMetadataTrait::class.java

        val getName = cls.getMethod("getName", traitCls)
        assertEquals("Default name", getName.invoke(null, AllDefaults))

        val getDesc = cls.getMethod("getDescription", traitCls)
        assertEquals("Default description", getDesc.invoke(null, AllDefaults))

        val getVer = cls.getMethod("getVersion", traitCls)
        assertEquals("Default version", getVer.invoke(null, AllDefaults))

        val getTags = cls.getMethod("getTags", traitCls)
        @Suppress("UNCHECKED_CAST")
        val tags = getTags.invoke(null, AllDefaults) as List<String>
        assertTrue(tags.isEmpty())
    }
}
