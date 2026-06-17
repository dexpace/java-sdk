/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseValidationTest {
    @Test
    fun `INHERIT coalesces to the default`() {
        assertEquals(ResponseValidation.ENABLED, ResponseValidation.INHERIT.applyDefault(ResponseValidation.ENABLED))
        assertEquals(ResponseValidation.DISABLED, ResponseValidation.INHERIT.applyDefault(ResponseValidation.DISABLED))
        assertEquals(ResponseValidation.INHERIT, ResponseValidation.INHERIT.applyDefault(ResponseValidation.INHERIT))
    }

    @Test
    fun `explicit choice overrides the default`() {
        assertEquals(ResponseValidation.ENABLED, ResponseValidation.ENABLED.applyDefault(ResponseValidation.DISABLED))
        assertEquals(ResponseValidation.DISABLED, ResponseValidation.DISABLED.applyDefault(ResponseValidation.ENABLED))
        assertEquals(ResponseValidation.ENABLED, ResponseValidation.ENABLED.applyDefault(ResponseValidation.INHERIT))
    }
}
