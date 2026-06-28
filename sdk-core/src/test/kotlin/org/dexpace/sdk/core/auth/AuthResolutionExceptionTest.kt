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

class AuthResolutionExceptionTest {
    @Test
    fun `message names required and available schemes`() {
        val ex =
            AuthResolutionException(
                listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY),
                setOf(AuthScheme.BASIC),
            )
        val message = ex.message ?: ""
        assertTrue(message.contains("OAUTH2"))
        assertTrue(message.contains("API_KEY"))
        assertTrue(message.contains("BASIC"))
    }

    @Test
    fun `exposes required and available schemes`() {
        val ex =
            AuthResolutionException(
                listOf(AuthScheme.OAUTH2),
                setOf(AuthScheme.BASIC, AuthScheme.DIGEST),
            )
        assertEquals(listOf(AuthScheme.OAUTH2), ex.requiredSchemes)
        assertEquals(setOf(AuthScheme.BASIC, AuthScheme.DIGEST), ex.availableSchemes)
    }

    @Test
    fun `exposed collections are defensive copies`() {
        val required = mutableListOf(AuthScheme.OAUTH2)
        val available = mutableSetOf(AuthScheme.BASIC)
        val ex = AuthResolutionException(required, available)
        required.add(AuthScheme.API_KEY)
        available.add(AuthScheme.DIGEST)
        assertEquals(listOf(AuthScheme.OAUTH2), ex.requiredSchemes)
        assertEquals(setOf(AuthScheme.BASIC), ex.availableSchemes)
    }

    @Test
    fun `is an unchecked RuntimeException`() {
        // Assigning to a RuntimeException reference confirms the unchecked-throw contract
        // without an always-true is-check (which -Werror rejects).
        val ex: RuntimeException = AuthResolutionException(listOf(AuthScheme.OAUTH2), emptySet())
        assertTrue(ex.message!!.isNotBlank())
    }
}
