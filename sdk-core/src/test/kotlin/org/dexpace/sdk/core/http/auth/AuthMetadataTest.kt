/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthMetadataTest {
    @Test
    fun `single-scheme constructor populates schemes and uses empty defaults for oauth fields`() {
        val metadata = AuthMetadata(listOf(AuthScheme.BASIC))
        assertEquals(listOf(AuthScheme.BASIC), metadata.schemes)
        assertTrue(metadata.oauthScopes.isEmpty())
        assertTrue(metadata.oauthParams.isEmpty())
    }

    @Test
    fun `multi-scheme constructor preserves scheme order`() {
        val schemes = listOf(AuthScheme.DIGEST, AuthScheme.BASIC, AuthScheme.OAUTH2)
        val metadata = AuthMetadata(schemes)
        assertEquals(schemes, metadata.schemes)
    }

    @Test
    fun `constructor accepts oauth scopes and params`() {
        val scopes = listOf("read", "write")
        val params = mapOf<String, Any>("claims" to "{\"id_token\":{\"email\":null}}")
        val metadata = AuthMetadata(listOf(AuthScheme.OAUTH2), scopes, params)
        assertEquals(scopes, metadata.oauthScopes)
        assertEquals(params, metadata.oauthParams)
    }

    @Test
    fun `empty schemes list is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { AuthMetadata(emptyList()) }
        assertEquals("schemes must not be empty", ex.message)
    }

    @Test
    fun `empty schemes list with non-default oauth args is also rejected`() {
        // Confirms the require runs before any other init side-effects regardless of
        // how many constructor arguments are populated.
        assertFailsWith<IllegalArgumentException> {
            AuthMetadata(emptyList(), listOf("scope"), mapOf("x" to "y"))
        }
    }

    @Test
    fun `no-auth marker scheme is permitted`() {
        val metadata = AuthMetadata(listOf(AuthScheme.NO_AUTH))
        assertEquals(listOf(AuthScheme.NO_AUTH), metadata.schemes)
    }

    @Test
    fun `mutating the source schemes list after construction does not affect the instance`() {
        // I-3: schemes must be defensively copied in, so a retained caller collection can't
        // mutate the instance (or re-break the non-empty invariant) after construction.
        val source = mutableListOf(AuthScheme.BASIC, AuthScheme.DIGEST)
        val metadata = AuthMetadata(source)
        source.clear()
        source.add(AuthScheme.OAUTH2)
        assertEquals(listOf(AuthScheme.BASIC, AuthScheme.DIGEST), metadata.schemes)
    }

    @Test
    fun `mutating the source oauth collections after construction does not affect the instance`() {
        // I-3: oauthScopes and oauthParams are defensively copied in as well.
        val scopes = mutableListOf("read")
        val params = mutableMapOf<String, Any>("a" to "1")
        val metadata = AuthMetadata(listOf(AuthScheme.OAUTH2), scopes, params)
        scopes.add("write")
        params["b"] = "2"
        assertEquals(listOf("read"), metadata.oauthScopes)
        assertEquals(mapOf<String, Any>("a" to "1"), metadata.oauthParams)
    }

    @Test
    fun `exposed schemes view is unmodifiable`() {
        // I-3: the exposed collections are unmodifiable views — mutation via cast throws.
        val metadata = AuthMetadata(listOf(AuthScheme.BASIC))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (metadata.schemes as MutableList<AuthScheme>).add(AuthScheme.DIGEST)
        }
    }

    @Test
    fun `exposed oauth views are unmodifiable`() {
        val metadata =
            AuthMetadata(listOf(AuthScheme.OAUTH2), listOf("read"), mapOf("a" to "1"))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (metadata.oauthScopes as MutableList<String>).add("write")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (metadata.oauthParams as MutableMap<String, Any>)["b"] = "2"
        }
    }
}
