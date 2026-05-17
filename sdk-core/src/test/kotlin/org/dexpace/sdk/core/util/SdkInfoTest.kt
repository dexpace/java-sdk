package org.dexpace.sdk.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkInfoTest {
    @Test
    fun `javaVersion is non-empty`() {
        // System.getProperty("java.version") is guaranteed by the JVM spec; the SdkInfo
        // fallback of "unknown" only fires on a stripped-down JVM that disables it.
        assertTrue(SdkInfo.javaVersion.isNotEmpty(), "javaVersion must not be empty")
    }

    @Test
    fun `javaVendor is non-empty`() {
        assertTrue(SdkInfo.javaVendor.isNotEmpty(), "javaVendor must not be empty")
    }

    @Test
    fun `osName is non-empty`() {
        assertTrue(SdkInfo.osName.isNotEmpty(), "osName must not be empty")
    }

    @Test
    fun `sdkVersion is non-empty`() {
        // Resolves to either the JAR manifest's Implementation-Version (in a published
        // build) or the "unknown" fallback (in the local class-files test run). Either
        // way it must never be blank — downstream `joinToString(" ")` relies on this.
        assertTrue(SdkInfo.sdkVersion.isNotEmpty(), "sdkVersion must not be empty")
    }

    @Test
    fun `defaultTokens contains exactly two entries`() {
        val tokens = SdkInfo.defaultTokens()
        assertEquals(2, tokens.size, "defaultTokens must produce exactly [dexpace-sdk/x, jvm/y]; got $tokens")
    }

    @Test
    fun `defaultTokens first entry uses the dexpace-sdk prefix`() {
        val tokens = SdkInfo.defaultTokens()
        assertTrue(
            tokens[0].startsWith("dexpace-sdk/"),
            "first default token must start with `dexpace-sdk/`, got ${tokens[0]}",
        )
    }

    @Test
    fun `defaultTokens second entry uses the jvm prefix`() {
        val tokens = SdkInfo.defaultTokens()
        assertTrue(
            tokens[1].startsWith("jvm/"),
            "second default token must start with `jvm/`, got ${tokens[1]}",
        )
    }

    @Test
    fun `defaultTokens encodes the resolved sdkVersion and javaVersion`() {
        val tokens = SdkInfo.defaultTokens()
        assertEquals("dexpace-sdk/${SdkInfo.sdkVersion}", tokens[0])
        assertEquals("jvm/${SdkInfo.javaVersion}", tokens[1])
    }
}
