/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthResolutionTest {
    @Test
    fun `scheme shorthand mirrors the requirement scheme`() {
        val resolution =
            AuthResolution(AuthRequirement.of(AuthScheme.API_KEY), AuthDescriptorTier.OPERATION)
        assertEquals(AuthScheme.API_KEY, resolution.scheme)
    }

    @Test
    fun `isAnonymous is true only for NO_AUTH`() {
        assertTrue(
            AuthResolution(AuthRequirement.of(AuthScheme.NO_AUTH), AuthDescriptorTier.CLIENT)
                .isAnonymous,
        )
        assertFalse(
            AuthResolution(AuthRequirement.of(AuthScheme.OAUTH2), AuthDescriptorTier.CLIENT)
                .isAnonymous,
        )
    }

    @Test
    fun `value semantics`() {
        val a =
            AuthResolution(AuthRequirement.of(AuthScheme.OAUTH2), AuthDescriptorTier.PER_CALL)
        val b =
            AuthResolution(AuthRequirement.of(AuthScheme.OAUTH2), AuthDescriptorTier.PER_CALL)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
