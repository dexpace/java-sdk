/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.generics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BuilderChecksTest {
    @Test
    fun `returns the value when present`() {
        val value = "GET"
        assertEquals(value, checkRequired("method", value))
    }

    @Test
    fun `returns the same instance when present`() {
        val value = listOf(1, 2, 3)
        assertSame(value, checkRequired("items", value))
    }

    @Test
    fun `throws IllegalStateException naming the field when value is null`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                checkRequired<String>("method", null)
            }
        assertEquals("method is required", ex.message)
    }

    @Test
    fun `message uses the supplied field name verbatim`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                checkRequired<Int>("status", null)
            }
        assertEquals("status is required", ex.message)
    }
}
