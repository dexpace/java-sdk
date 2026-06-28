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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthRequirementTest {
    @Test
    fun `of populates scheme with empty oauth defaults`() {
        val req = AuthRequirement.of(AuthScheme.API_KEY)
        assertEquals(AuthScheme.API_KEY, req.scheme)
        assertTrue(req.oauthScopes.isEmpty())
        assertTrue(req.oauthParams.isEmpty())
    }

    @Test
    fun `of accepts oauth scopes and params`() {
        val req =
            AuthRequirement.of(AuthScheme.OAUTH2, listOf("read", "write"), mapOf("claims" to "x"))
        assertEquals(listOf("read", "write"), req.oauthScopes)
        assertEquals(mapOf<String, Any>("claims" to "x"), req.oauthParams)
    }

    @Test
    fun `mutating source collections after construction does not affect the instance`() {
        val scopes = mutableListOf("read")
        val params = mutableMapOf<String, Any>("a" to "1")
        val req = AuthRequirement.of(AuthScheme.OAUTH2, scopes, params)
        scopes.add("write")
        params["b"] = "2"
        assertEquals(listOf("read"), req.oauthScopes)
        assertEquals(mapOf<String, Any>("a" to "1"), req.oauthParams)
    }

    @Test
    fun `exposed collections are unmodifiable`() {
        val req = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read"), mapOf("a" to "1"))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (req.oauthScopes as MutableList<String>).add("write")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (req.oauthParams as MutableMap<String, Any>)["b"] = "2"
        }
    }

    @Test
    fun `builder requires a scheme`() {
        val ex = assertFailsWith<IllegalStateException> { AuthRequirement.Builder().build() }
        assertEquals("scheme is required", ex.message)
    }

    @Test
    fun `builder sets all fields`() {
        val req =
            AuthRequirement.Builder()
                .scheme(AuthScheme.OAUTH2)
                .oauthScopes(listOf("read"))
                .oauthParams(mapOf("a" to "1"))
                .build()
        assertEquals(AuthScheme.OAUTH2, req.scheme)
        assertEquals(listOf("read"), req.oauthScopes)
        assertEquals(mapOf<String, Any>("a" to "1"), req.oauthParams)
    }

    @Test
    fun `builder setters defensively copy so a later source mutation does not leak`() {
        val scopes = mutableListOf("read")
        val params = mutableMapOf<String, Any>("a" to "1")
        val builder =
            AuthRequirement.Builder()
                .scheme(AuthScheme.OAUTH2)
                .oauthScopes(scopes)
                .oauthParams(params)
        // Mutating the sources after the setter call, before build(), must not leak through.
        scopes.add("write")
        params["b"] = "2"
        val req = builder.build()
        assertEquals(listOf("read"), req.oauthScopes)
        assertEquals(mapOf<String, Any>("a" to "1"), req.oauthParams)
    }

    @Test
    fun `newBuilder round-trips state`() {
        val original = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read"), mapOf("a" to "1"))
        val copy = original.newBuilder().build()
        assertEquals(original, copy)
    }

    @Test
    fun `newBuilder allows overriding a single field`() {
        val original = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read"))
        val modified = original.newBuilder().oauthScopes(listOf("write")).build()
        assertEquals(AuthScheme.OAUTH2, modified.scheme)
        assertEquals(listOf("write"), modified.oauthScopes)
    }

    @Test
    fun `equals and hashCode reflect value semantics`() {
        val a = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read"))
        val b = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read"))
        val c = AuthRequirement.of(AuthScheme.OAUTH2, listOf("write"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `toString includes scheme and oauth fields`() {
        val text = AuthRequirement.of(AuthScheme.OAUTH2, listOf("read")).toString()
        assertTrue(text.contains("OAUTH2"))
        assertTrue(text.contains("read"))
    }
}
