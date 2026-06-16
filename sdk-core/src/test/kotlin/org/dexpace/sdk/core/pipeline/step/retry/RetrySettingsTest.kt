/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Validation tests for [RetrySettings.RetrySettingsBuilder]. The builder is the only public
 * construction path (the constructor is private), so it is the place to reject a delay too
 * large to convert to nanoseconds — otherwise the value overflows [Duration.toNanos] deep in
 * the backoff math and throws [ArithmeticException] mid-retry.
 */
class RetrySettingsTest {
    @Test
    fun `maxDelay beyond the nanosecond range is rejected`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                RetrySettings.builder().maxDelay(Duration.ofSeconds(Long.MAX_VALUE)).build()
            }
        assertEquals(true, ex.message?.contains("maxDelay"), "message should name the offending field: ${ex.message}")
    }

    @Test
    fun `initialDelay beyond the nanosecond range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetrySettings.builder().initialDelay(Duration.ofSeconds(Long.MAX_VALUE)).build()
        }
    }

    @Test
    fun `totalTimeout beyond the nanosecond range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetrySettings.builder().totalTimeout(Duration.ofSeconds(Long.MAX_VALUE)).build()
        }
    }

    @Test
    fun `the maximum nanosecond-representable delay is accepted at the boundary`() {
        // Duration.ofNanos(Long.MAX_VALUE).toNanos() is exactly Long.MAX_VALUE — the boundary must
        // be accepted; only strictly-larger values are rejected.
        val boundary = Duration.ofNanos(Long.MAX_VALUE)
        val settings = RetrySettings.builder().maxDelay(boundary).build()
        assertEquals(boundary, settings.maxDelay)
    }

    @Test
    fun `ordinary durations are still accepted`() {
        val settings =
            RetrySettings.builder()
                .initialDelay(Duration.ofMillis(200))
                .maxDelay(Duration.ofSeconds(8))
                .totalTimeout(Duration.ofSeconds(30))
                .build()
        assertEquals(Duration.ofSeconds(8), settings.maxDelay)
    }

    // --- Canonical shared defaults (one source of truth for both retry stacks) --------------

    @Test
    fun `defaults match the canonical shared constants`() {
        val settings = RetrySettings.defaults()
        assertEquals(Duration.ofSeconds(30), settings.totalTimeout, "totalTimeout")
        assertEquals(RetrySettings.DEFAULT_INITIAL_DELAY, settings.initialDelay, "initialDelay")
        assertEquals(Duration.ofMillis(200), settings.initialDelay, "initialDelay value")
        assertEquals(RetrySettings.DEFAULT_MAX_DELAY, settings.maxDelay, "maxDelay")
        assertEquals(Duration.ofSeconds(8), settings.maxDelay, "maxDelay value")
        assertEquals(RetrySettings.DEFAULT_DELAY_MULTIPLIER, settings.delayMultiplier, "delayMultiplier")
        assertEquals(2.0, settings.delayMultiplier, "delayMultiplier value")
        assertEquals(RetrySettings.DEFAULT_JITTER, settings.jitter, "jitter")
        assertEquals(0.2, settings.jitter, "jitter value")
        assertEquals(RetrySettings.DEFAULT_MAX_ATTEMPTS, settings.maxAttempts, "maxAttempts")
        assertEquals(3, settings.maxAttempts, "maxAttempts value")
    }

    @Test
    fun `default retryable statuses include 408 and the common retryable codes`() {
        // The reconciled stance: 408 IS retryable, matching RetryUtils / HttpException.isRetryable
        // and the stage-based DefaultRetryStep. The two stacks must agree on the 408 question.
        val statuses = RetrySettings.DEFAULT_RETRYABLE_STATUSES
        assertEquals(setOf(408, 429, 500, 502, 503, 504), statuses.toSet())
    }
}
