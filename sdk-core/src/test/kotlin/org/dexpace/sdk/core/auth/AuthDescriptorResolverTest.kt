/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthDescriptorResolverTest {
    private val resolver = AuthDescriptorResolver.INSTANCE

    private fun descriptor(vararg schemes: AuthScheme): AuthDescriptor = AuthDescriptor.ofSchemes(schemes.toList())

    // ---- tier precedence ------------------------------------------------------------------

    @Test
    fun `per-call descriptor wins over operation and client`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.OAUTH2, AuthScheme.API_KEY, AuthScheme.BASIC),
                perCall = descriptor(AuthScheme.BASIC),
                operation = descriptor(AuthScheme.API_KEY),
                client = descriptor(AuthScheme.OAUTH2),
            )
        assertEquals(AuthDescriptorTier.PER_CALL, resolution.tier)
        assertEquals(AuthScheme.BASIC, resolution.scheme)
    }

    @Test
    fun `operation descriptor wins over client when per-call is absent`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.API_KEY, AuthScheme.OAUTH2),
                operation = descriptor(AuthScheme.API_KEY),
                client = descriptor(AuthScheme.OAUTH2),
            )
        assertEquals(AuthDescriptorTier.OPERATION, resolution.tier)
        assertEquals(AuthScheme.API_KEY, resolution.scheme)
    }

    @Test
    fun `client descriptor is used when nothing more specific is supplied`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.OAUTH2),
                client = descriptor(AuthScheme.OAUTH2),
            )
        assertEquals(AuthDescriptorTier.CLIENT, resolution.tier)
        assertEquals(AuthScheme.OAUTH2, resolution.scheme)
    }

    @Test
    fun `a present higher tier is resolved against even when a lower tier would succeed`() {
        // per-call lists only BASIC which is NOT available; the operation lists OAUTH2 which IS.
        // The ladder must NOT fall through: the explicit per-call override fails outright.
        val ex =
            assertFailsWith<AuthResolutionException> {
                resolver.resolve(
                    availableSchemes = setOf(AuthScheme.OAUTH2),
                    perCall = descriptor(AuthScheme.BASIC),
                    operation = descriptor(AuthScheme.OAUTH2),
                )
            }
        assertEquals(listOf(AuthScheme.BASIC), ex.requiredSchemes)
    }

    // ---- requirement precedence within a descriptor ---------------------------------------

    @Test
    fun `first satisfiable requirement in declared order wins`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.OAUTH2, AuthScheme.API_KEY),
                operation = descriptor(AuthScheme.OAUTH2, AuthScheme.API_KEY),
            )
        assertEquals(AuthScheme.OAUTH2, resolution.scheme)
    }

    @Test
    fun `unsatisfiable leading requirement is skipped for the next satisfiable one`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.API_KEY),
                operation = descriptor(AuthScheme.OAUTH2, AuthScheme.API_KEY),
            )
        assertEquals(AuthScheme.API_KEY, resolution.scheme)
    }

    @Test
    fun `forwards oauth parameters from the resolved requirement`() {
        val operation =
            AuthDescriptor.of(
                listOf(
                    AuthRequirement.of(AuthScheme.OAUTH2, listOf("read", "write"), mapOf("a" to "1")),
                ),
            )
        val resolution =
            resolver.resolve(availableSchemes = setOf(AuthScheme.OAUTH2), operation = operation)
        assertEquals(listOf("read", "write"), resolution.requirement.oauthScopes)
        assertEquals(mapOf<String, Any>("a" to "1"), resolution.requirement.oauthParams)
    }

    // ---- anonymous handling ---------------------------------------------------------------

    @Test
    fun `NO_AUTH is always satisfiable without any available scheme`() {
        val resolution =
            resolver.resolve(
                availableSchemes = emptySet(),
                operation = descriptor(AuthScheme.NO_AUTH),
            )
        assertTrue(resolution.isAnonymous)
        assertEquals(AuthScheme.NO_AUTH, resolution.scheme)
    }

    @Test
    fun `a real scheme is preferred over a trailing NO_AUTH fallback when available`() {
        val resolution =
            resolver.resolve(
                availableSchemes = setOf(AuthScheme.OAUTH2),
                operation = descriptor(AuthScheme.OAUTH2, AuthScheme.NO_AUTH),
            )
        assertEquals(AuthScheme.OAUTH2, resolution.scheme)
        assertTrue(!resolution.isAnonymous)
    }

    @Test
    fun `NO_AUTH fallback resolves anonymously when the real scheme is unavailable`() {
        val resolution =
            resolver.resolve(
                availableSchemes = emptySet(),
                operation = descriptor(AuthScheme.OAUTH2, AuthScheme.NO_AUTH),
            )
        assertTrue(resolution.isAnonymous)
    }

    // ---- failure / argument validation ----------------------------------------------------

    @Test
    fun `no satisfiable scheme raises a tailored error naming required and available`() {
        val ex =
            assertFailsWith<AuthResolutionException> {
                resolver.resolve(
                    availableSchemes = setOf(AuthScheme.BASIC),
                    operation = descriptor(AuthScheme.OAUTH2, AuthScheme.API_KEY),
                )
            }
        assertEquals(listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY), ex.requiredSchemes)
        assertEquals(setOf(AuthScheme.BASIC), ex.availableSchemes)
        val message = ex.message ?: ""
        assertTrue(message.contains("OAUTH2"))
        assertTrue(message.contains("API_KEY"))
        assertTrue(message.contains("BASIC"))
    }

    @Test
    fun `all-null descriptors raises IllegalArgumentException`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                resolver.resolve(availableSchemes = setOf(AuthScheme.OAUTH2))
            }
        assertTrue(ex.message!!.contains("at least one"))
    }

    @Test
    fun `shared INSTANCE is reusable across calls`() {
        val a =
            AuthDescriptorResolver.INSTANCE.resolve(
                availableSchemes = setOf(AuthScheme.OAUTH2),
                client = descriptor(AuthScheme.OAUTH2),
            )
        val b =
            AuthDescriptorResolver.INSTANCE.resolve(
                availableSchemes = setOf(AuthScheme.API_KEY),
                client = descriptor(AuthScheme.API_KEY),
            )
        assertEquals(AuthScheme.OAUTH2, a.scheme)
        assertEquals(AuthScheme.API_KEY, b.scheme)
    }
}
