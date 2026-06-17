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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthDescriptorTest {
    @Test
    fun `ofSchemes preserves preference order`() {
        val descriptor =
            AuthDescriptor.ofSchemes(
                listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY, AuthScheme.BASIC),
            )
        assertEquals(
            listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY, AuthScheme.BASIC),
            descriptor.schemes,
        )
    }

    @Test
    fun `of preserves requirement order and exposes schemes view`() {
        val requirements =
            listOf(
                AuthRequirement(AuthScheme.OAUTH2, listOf("read")),
                AuthRequirement.of(AuthScheme.API_KEY),
            )
        val descriptor = AuthDescriptor.of(requirements)
        assertEquals(requirements, descriptor.requirements)
        assertEquals(listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY), descriptor.schemes)
    }

    @Test
    fun `empty requirements list is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { AuthDescriptor.of(emptyList()) }
        assertEquals("requirements must not be empty", ex.message)
    }

    @Test
    fun `empty schemes list is rejected`() {
        assertFailsWith<IllegalArgumentException> { AuthDescriptor.ofSchemes(emptyList()) }
    }

    @Test
    fun `builder with no requirements is rejected on build`() {
        assertFailsWith<IllegalArgumentException> { AuthDescriptor.Builder().build() }
    }

    @Test
    fun `allowsAnonymous is true only when NO_AUTH is present`() {
        assertFalse(AuthDescriptor.ofSchemes(listOf(AuthScheme.BASIC)).allowsAnonymous())
        assertTrue(
            AuthDescriptor.ofSchemes(listOf(AuthScheme.BASIC, AuthScheme.NO_AUTH))
                .allowsAnonymous(),
        )
    }

    @Test
    fun `builder addScheme and addRequirement keep insertion order`() {
        val descriptor =
            AuthDescriptor.Builder()
                .addScheme(AuthScheme.OAUTH2)
                .addRequirement(AuthRequirement.of(AuthScheme.API_KEY))
                .addScheme(AuthScheme.BASIC)
                .build()
        assertEquals(
            listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY, AuthScheme.BASIC),
            descriptor.schemes,
        )
    }

    @Test
    fun `builder requirements replaces all prior entries`() {
        val descriptor =
            AuthDescriptor.Builder()
                .addScheme(AuthScheme.OAUTH2)
                .requirements(listOf(AuthRequirement.of(AuthScheme.BASIC)))
                .build()
        assertEquals(listOf(AuthScheme.BASIC), descriptor.schemes)
    }

    @Test
    fun `newBuilder round-trips requirements`() {
        val original =
            AuthDescriptor.of(
                listOf(
                    AuthRequirement(AuthScheme.OAUTH2, listOf("read")),
                    AuthRequirement.of(AuthScheme.API_KEY),
                ),
            )
        assertEquals(original, original.newBuilder().build())
    }

    @Test
    fun `newBuilder can append an alternative`() {
        val original = AuthDescriptor.ofSchemes(listOf(AuthScheme.OAUTH2))
        val extended = original.newBuilder().addScheme(AuthScheme.API_KEY).build()
        assertEquals(listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY), extended.schemes)
    }

    @Test
    fun `requirements view is unmodifiable and defensively copied`() {
        val source = mutableListOf(AuthRequirement.of(AuthScheme.OAUTH2))
        val descriptor = AuthDescriptor.of(source)
        source.add(AuthRequirement.of(AuthScheme.API_KEY))
        assertEquals(listOf(AuthScheme.OAUTH2), descriptor.schemes)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (descriptor.requirements as MutableList<AuthRequirement>)
                .add(AuthRequirement.of(AuthScheme.BASIC))
        }
    }

    @Test
    fun `equals and hashCode reflect requirement order`() {
        val a = AuthDescriptor.ofSchemes(listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY))
        val b = AuthDescriptor.ofSchemes(listOf(AuthScheme.OAUTH2, AuthScheme.API_KEY))
        val reordered = AuthDescriptor.ofSchemes(listOf(AuthScheme.API_KEY, AuthScheme.OAUTH2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, reordered)
    }

    @Test
    fun `toString includes the requirements`() {
        val text = AuthDescriptor.ofSchemes(listOf(AuthScheme.BASIC)).toString()
        assertTrue(text.contains("BASIC"))
    }
}
