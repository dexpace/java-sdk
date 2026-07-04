/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.request.Method
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpRedirectOptionsBuilderTest {
    @Test
    fun `builder sets maxHops and leaves other fields at defaults`() {
        val defaults = HttpRedirectOptions()
        val opts = HttpRedirectOptions.builder().maxHops(5).build()

        assertEquals(5, opts.maxHops)
        assertEquals(defaults.allowedMethods, opts.allowedMethods)
        assertEquals(defaults.locationHeader, opts.locationHeader)
        assertEquals(defaults.follow303, opts.follow303)
        assertEquals(defaults.allowSchemeDowngrade, opts.allowSchemeDowngrade)
        assertNull(opts.shouldRedirect)
    }

    @Test
    fun `newBuilder round-trips scalar fields`() {
        val original =
            HttpRedirectOptions
                .builder()
                .maxHops(5)
                .follow303(true)
                .allowSchemeDowngrade(true)
                .allowedMethods(EnumSet.of(Method.GET, Method.HEAD, Method.POST))
                .build()
        val copy = original.newBuilder().build()

        assertEquals(5, copy.maxHops)
        assertEquals(true, copy.follow303)
        assertEquals(true, copy.allowSchemeDowngrade)
        assertEquals(setOf(Method.GET, Method.HEAD, Method.POST), copy.allowedMethods)
    }
}
