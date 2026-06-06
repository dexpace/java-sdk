/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Covers the placeholder [QueryParam] class. The class is currently a stub — its
 * single method `implementation()` throws `NotImplementedError` via `TODO()`. The
 * test exists to (a) bring the type onto the coverage instrumentation's radar and
 * (b) document the placeholder behaviour so a real implementation arrives with a
 * test that should be replaced rather than added alongside.
 */
class QueryParamTest {
    @Test
    fun `default constructor produces an instance`() {
        // The no-arg constructor itself must succeed even though all methods are TODO.
        assertNotNull(QueryParam())
    }

    @Test
    fun `implementation method throws NotImplementedError`() {
        // `TODO()` raises kotlin.NotImplementedError. Verify the contract so callers
        // know touching this method is an explicit "not ready" signal.
        assertFailsWith<NotImplementedError> {
            QueryParam().implementation()
        }
    }
}
