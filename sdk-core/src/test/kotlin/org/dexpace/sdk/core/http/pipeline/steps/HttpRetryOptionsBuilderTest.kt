/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.config.Configuration
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRetryOptionsBuilderTest {
    @Test
    fun `builder sets maxRetries and leaves other fields at defaults`() {
        val defaults = HttpRetryOptions()
        val opts = HttpRetryOptions.builder().maxRetries(5).build()

        assertEquals(5, opts.maxRetries)
        assertEquals(defaults.baseDelay, opts.baseDelay)
        assertEquals(defaults.maxDelay, opts.maxDelay)
        assertEquals(defaults.fixedDelay, opts.fixedDelay)
        assertEquals(defaults.retryAfterHeaders, opts.retryAfterHeaders)
    }

    @Test
    fun `newBuilder round-trips scalar fields`() {
        val original =
            HttpRetryOptions
                .builder()
                .maxRetries(7)
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(30))
                .fixedDelay(Duration.ofMillis(500))
                .build()
        val copy = original.newBuilder().build()

        assertEquals(7, copy.maxRetries)
        assertEquals(Duration.ofSeconds(1), copy.baseDelay)
        assertEquals(Duration.ofSeconds(30), copy.maxDelay)
        assertEquals(Duration.ofMillis(500), copy.fixedDelay)
    }

    @Test
    fun `fromConfiguration reads max retry attempts`() {
        val config = Configuration.builder().put(Configuration.MAX_RETRY_ATTEMPTS, "5").build()
        val opts = HttpRetryOptions.fromConfiguration(config)
        assertEquals(5, opts.maxRetries)
    }

    @Test
    fun `fromConfiguration defaults when key absent`() {
        val config = Configuration.builder().build()
        val defaults = HttpRetryOptions()
        val opts = HttpRetryOptions.fromConfiguration(config)
        assertEquals(defaults.maxRetries, opts.maxRetries)
    }
}
