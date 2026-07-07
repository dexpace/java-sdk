/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.instrumentation.ClientLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryPolicySupportTest {
    private val logger = ClientLogger(RetryPolicySupportTest::class)

    private fun support(configuredMaxRetries: Int): RetryPolicySupport =
        RetryPolicySupport(HttpRetryOptions(maxRetries = configuredMaxRetries), logger)

    private fun options(maxRetries: Int?): RequestOptions = RequestOptions.builder().maxRetries(maxRetries).build()

    @Test
    fun `null per-call override falls back to the configured budget`() {
        // RequestOptions.EMPTY carries a null maxRetries — no per-call override, so the
        // configured budget applies.
        assertEquals(5, support(configuredMaxRetries = 5).effectiveMaxRetries(RequestOptions.EMPTY))
    }

    @Test
    fun `negative per-call override falls back to the configured budget, not zero retries`() {
        // A negative per-call value means "use the configured default", mirroring how a negative
        // CONFIGURED maxRetries is clamped — it must NOT collapse to 0 retries.
        assertEquals(5, support(configuredMaxRetries = 5).effectiveMaxRetries(options(-1)))
        assertEquals(5, support(configuredMaxRetries = 5).effectiveMaxRetries(options(-100)))
    }

    @Test
    fun `zero per-call override yields zero retries`() {
        // 0 is a real, honoured value: fail fast, exactly one attempt. It is NOT treated as
        // "unset".
        assertEquals(0, support(configuredMaxRetries = 5).effectiveMaxRetries(options(0)))
    }

    @Test
    fun `positive per-call override is used verbatim`() {
        assertEquals(1, support(configuredMaxRetries = 5).effectiveMaxRetries(options(1)))
        assertEquals(9, support(configuredMaxRetries = 5).effectiveMaxRetries(options(9)))
    }

    @Test
    fun `null override returns the already-clamped configured default when the configured value is negative`() {
        // A negative CONFIGURED maxRetries is clamped to DEFAULT_MAX_RETRIES at construction, so a
        // null per-call override resolves to the clamp — not to the raw negative value.
        val support = support(configuredMaxRetries = -5)
        assertEquals(
            DefaultRetryStep.DEFAULT_MAX_RETRIES,
            support.effectiveMaxRetries(RequestOptions.EMPTY),
        )
    }
}
