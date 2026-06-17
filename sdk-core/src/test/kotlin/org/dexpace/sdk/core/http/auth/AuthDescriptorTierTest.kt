/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthDescriptorTierTest {
    @Test
    fun `tiers are declared from highest to lowest precedence`() {
        assertEquals(
            listOf(
                AuthDescriptorTier.PER_CALL,
                AuthDescriptorTier.OPERATION,
                AuthDescriptorTier.CLIENT,
            ),
            AuthDescriptorTier.entries.toList(),
        )
    }

    @Test
    fun `valueOf round-trips each tier`() {
        for (tier in AuthDescriptorTier.entries) {
            assertEquals(tier, AuthDescriptorTier.valueOf(tier.name))
        }
    }
}
