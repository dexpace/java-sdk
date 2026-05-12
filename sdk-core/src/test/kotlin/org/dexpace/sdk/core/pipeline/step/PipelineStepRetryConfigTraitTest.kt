package org.dexpace.sdk.core.pipeline.step

import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [PipelineStepRetryConfigTrait]'s default property implementations. The
 * trait is a configuration mixin — its defaults are the public contract a step
 * gets for free when it does not override; locking them down in a test prevents
 * silent behaviour drift from the SDK's defaults.
 *
 * Reads each default through an anonymous implementation that overrides nothing
 * so every default-getter is exercised exactly once.
 */
class PipelineStepRetryConfigTraitTest {

    /**
     * Bare implementation — overrides nothing. Every property read goes through
     * the default getter in the trait, which is what we want to cover.
     */
    private object AllDefaults : PipelineStepRetryConfigTrait

    @Test
    fun `default timeoutMilliseconds is 10000`() {
        assertEquals(10000L, AllDefaults.timeoutMilliseconds)
    }

    @Test
    fun `default exponentialBackoff is false`() {
        assertFalse(AllDefaults.exponentialBackoff)
    }

    @Test
    fun `default initialBackoffMilliseconds is 1000`() {
        assertEquals(1000L, AllDefaults.initialBackoffMilliseconds)
    }

    @Test
    fun `default maxBackoffMilliseconds is 10000`() {
        assertEquals(10000L, AllDefaults.maxBackoffMilliseconds)
    }

    @Test
    fun `default multiplier is 2 point 0`() {
        assertEquals(2.0, AllDefaults.multiplier)
    }

    @Test
    fun `default maxRetries is 3`() {
        assertEquals(3, AllDefaults.maxRetries)
    }

    @Test
    fun `default retryOnExceptions covers IOException and TimeoutException`() {
        val classes = AllDefaults.retryOnExceptions
        assertEquals(2, classes.size, "expected exactly two default retry classes")
        assertTrue(IOException::class.java in classes)
        assertTrue(TimeoutException::class.java in classes)
    }

    @Test
    fun `trait implements PipelineStepConfigTrait`() {
        // The marker root anchors the config trait family — assert structural
        // type relation so a refactor that breaks the inheritance trips this test.
        val trait: PipelineStepConfigTrait = AllDefaults
        assertSame(AllDefaults, trait)
    }

    @Test
    fun `overriding a single default leaves the others at their defaults`() {
        val custom = object : PipelineStepRetryConfigTrait {
            override val maxRetries: Int = 7
        }
        assertEquals(7, custom.maxRetries)
        // Untouched defaults still resolve through the trait getter.
        assertEquals(10000L, custom.timeoutMilliseconds)
        assertEquals(2.0, custom.multiplier)
        assertFalse(custom.exponentialBackoff)
    }

    // ----- $DefaultImpls bridge: invoked by Java callers -----

    /**
     * Kotlin compiles default interface methods into `Trait$DefaultImpls` static methods so
     * pre-Java-8 callers (and the JVM-target-1.8 build here) can dispatch them. Coverage
     * sees these as separate methods from the Kotlin getters; reflectively invoking them
     * exercises the bridge path explicitly.
     */
    @Test
    fun `DefaultImpls bridge methods return the trait defaults`() {
        val cls = Class.forName(
            "org.dexpace.sdk.core.pipeline.step.PipelineStepRetryConfigTrait\$DefaultImpls",
        )
        val traitCls = PipelineStepRetryConfigTrait::class.java

        // Each `$DefaultImpls.getX(trait)` is a static method that takes the receiver.
        val getMaxRetries = cls.getMethod("getMaxRetries", traitCls)
        assertEquals(3, getMaxRetries.invoke(null, AllDefaults))

        val getTimeout = cls.getMethod("getTimeoutMilliseconds", traitCls)
        assertEquals(10000L, getTimeout.invoke(null, AllDefaults))

        val getInitial = cls.getMethod("getInitialBackoffMilliseconds", traitCls)
        assertEquals(1000L, getInitial.invoke(null, AllDefaults))

        val getMax = cls.getMethod("getMaxBackoffMilliseconds", traitCls)
        assertEquals(10000L, getMax.invoke(null, AllDefaults))

        val getExp = cls.getMethod("getExponentialBackoff", traitCls)
        assertEquals(false, getExp.invoke(null, AllDefaults))

        val getMult = cls.getMethod("getMultiplier", traitCls)
        assertEquals(2.0, getMult.invoke(null, AllDefaults))

        val getRetryOn = cls.getMethod("getRetryOnExceptions", traitCls)
        @Suppress("UNCHECKED_CAST")
        val list = getRetryOn.invoke(null, AllDefaults) as List<Class<*>>
        assertEquals(2, list.size)
        assertTrue(IOException::class.java in list)
        assertTrue(TimeoutException::class.java in list)
    }
}
