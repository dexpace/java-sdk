/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BearerTokenProviderTest {
    /**
     * Provider implementation that captures invocation arguments so we can verify the
     * single-arg `fetch(scopes)` default overload forwards to the two-arg form with an
     * empty params map.
     */
    private class CapturingProvider : BearerTokenProvider {
        var lastScopes: List<String>? = null
            private set
        var lastParams: Map<String, Any>? = null
            private set

        override fun fetch(
            scopes: List<String>,
            params: Map<String, Any>,
        ): BearerToken {
            lastScopes = scopes
            lastParams = params
            return BearerToken("tk", null)
        }
    }

    @Test
    fun `single-arg fetch forwards to two-arg fetch with empty params`() {
        val provider = CapturingProvider()
        val token = provider.fetch(listOf("read"))
        assertEquals("tk", token.token)
        assertEquals(listOf("read"), provider.lastScopes)
        assertTrue(provider.lastParams!!.isEmpty(), "single-arg overload should pass empty params")
    }

    @Test
    fun `single-arg fetch with empty scope list also works`() {
        val provider = CapturingProvider()
        provider.fetch(emptyList())
        assertEquals(emptyList(), provider.lastScopes)
        assertTrue(provider.lastParams!!.isEmpty())
    }

    @Test
    fun `two-arg fetch passes through explicit params`() {
        val provider = CapturingProvider()
        val params = mapOf<String, Any>("audience" to "https://api")
        provider.fetch(listOf("scope1"), params)
        assertEquals(listOf("scope1"), provider.lastScopes)
        assertEquals(params, provider.lastParams)
    }

    @Test
    fun `fun-interface lambda satisfies BearerTokenProvider`() {
        // Confirms the SAM is usable from Kotlin as a lambda.
        val provider = BearerTokenProvider { _, _ -> BearerToken("from-lambda", null) }
        val token = provider.fetch(listOf("s"))
        assertEquals("from-lambda", token.token)
    }
}
