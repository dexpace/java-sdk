/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.example

import kotlin.test.Test

/**
 * Smoke test for [runSimpleGet]: asserts the minimal GET sample runs deterministically against an
 * embedded server without throwing.
 */
class SimpleGetAppTest {
    @Test
    fun minimalGetRunsWithoutThrowing() {
        installIoProvider()
        runSimpleGet()
    }
}
