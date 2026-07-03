/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.DroppedHeaderLogging
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit-tests the [DroppedHeaderLogging] decision on [RequestAdapter]. The transport test modules run
 * on `slf4j-nop`, so emitted log output cannot be captured; the dedup decision that drives the log
 * level is asserted directly through the internal `shouldWarnForDroppedHeader`.
 */
class RequestAdapterDroppedHeaderLoggingTest {
    private fun adapter(policy: DroppedHeaderLogging): RequestAdapter =
        RequestAdapter(ClientLogger("test.okhttp.RequestAdapter"), policy)

    @Test
    fun `PER_OCCURRENCE warns on every drop`() {
        val adapter = adapter(DroppedHeaderLogging.PER_OCCURRENCE)
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Foo"))
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Foo"), "every drop of the same header still warns")
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Bar"))
    }

    @Test
    fun `ONCE_PER_HEADER warns on the first drop per name then goes verbose, case-insensitively`() {
        val adapter = adapter(DroppedHeaderLogging.ONCE_PER_HEADER)
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Foo"), "first drop of a header warns")
        assertFalse(adapter.shouldWarnForDroppedHeader("X-Foo"), "repeat drop of the same header is verbose")
        assertFalse(adapter.shouldWarnForDroppedHeader("x-foo"), "dedup is case-insensitive")
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Bar"), "a different header still warns once")
        assertFalse(adapter.shouldWarnForDroppedHeader("X-Bar"))
    }

    @Test
    fun `VERBOSE_ONLY never warns`() {
        val adapter = adapter(DroppedHeaderLogging.VERBOSE_ONLY)
        assertFalse(adapter.shouldWarnForDroppedHeader("X-Foo"))
        assertFalse(adapter.shouldWarnForDroppedHeader("X-Foo"))
    }

    @Test
    fun `the default policy is ONCE_PER_HEADER`() {
        val adapter = RequestAdapter(ClientLogger("test.okhttp.RequestAdapter"))
        assertTrue(adapter.shouldWarnForDroppedHeader("X-Foo"), "first drop warns under the default")
        assertFalse(adapter.shouldWarnForDroppedHeader("X-Foo"), "default dedups after the first warn")
    }
}
