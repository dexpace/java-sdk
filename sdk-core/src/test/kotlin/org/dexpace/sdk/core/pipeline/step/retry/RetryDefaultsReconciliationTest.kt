/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step.retry

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep
import org.dexpace.sdk.core.http.pipeline.steps.HttpRetryOptions
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.io.OkioIoProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Guards that the two retry stacks — the stage-based [DefaultRetryStep]/[HttpRetryOptions] and
 * the recovery-aware [RetryStep]/[RetrySettings] — share ONE set of documented defaults and ONE
 * backoff computation ([BackoffCalculator]). If a future change re-introduces divergent defaults
 * (max attempts, base/initial delay, max delay, multiplier, jitter) or a second backoff formula,
 * these assertions fail.
 */
class RetryDefaultsReconciliationTest {
    @BeforeEach
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    // region -- shared default constants --

    @Test
    fun `both stacks share the same base delay default`() {
        assertEquals(
            RetrySettings.DEFAULT_INITIAL_DELAY,
            HttpRetryOptions().baseDelay,
            "stage-based baseDelay default must equal the recovery-aware initialDelay default",
        )
        assertEquals(Duration.ofMillis(200), HttpRetryOptions().baseDelay)
    }

    @Test
    fun `both stacks share the same max delay default`() {
        assertEquals(
            RetrySettings.DEFAULT_MAX_DELAY,
            HttpRetryOptions().maxDelay,
            "stage-based maxDelay default must equal the recovery-aware maxDelay default",
        )
        assertEquals(Duration.ofSeconds(8), HttpRetryOptions().maxDelay)
    }

    @Test
    fun `both stacks default to the same total send budget`() {
        // The two knobs count differently by design (retries vs total attempts), but the default
        // budget must be the same number of sends: initial + DEFAULT_MAX_RETRIES == DEFAULT_MAX_ATTEMPTS.
        assertEquals(
            RetrySettings.DEFAULT_MAX_ATTEMPTS,
            DefaultRetryStep.DEFAULT_MAX_RETRIES + 1,
            "default total sends must agree across stacks (1 initial + retries == maxAttempts)",
        )
    }

    // endregion

    // region -- shared backoff sequence --

    /**
     * The canonical no-jitter schedule for `initial=100ms, multiplier=2.0, maxDelay=1s`:
     * attempt N (1-indexed) → `100ms * 2^(N-1)`, capped at 1s. Indexed by attempt-1.
     */
    private val expectedSeriesMs = longArrayOf(100, 200, 400, 800, 1000, 1000)

    @Test
    fun `BackoffCalculator produces the canonical exponential sequence`() {
        val settings =
            RetrySettings.builder()
                .initialDelay(Duration.ofMillis(BASE_MS))
                .delayMultiplier(RetrySettings.DEFAULT_DELAY_MULTIPLIER)
                .maxDelay(Duration.ofSeconds(1))
                .jitter(0.0)
                .totalTimeout(Duration.ZERO)
                .maxAttempts(10)
                .build()
        for (attempt in 1..expectedSeriesMs.size) {
            assertEquals(
                Duration.ofMillis(expectedSeriesMs[attempt - 1]),
                BackoffCalculator.computeDelay(attempt, settings),
                "attempt $attempt",
            )
        }
    }

    @Test
    fun `stage-based step follows the same exponential sequence via BackoffCalculator`() {
        // Drive the stage-based step and observe the cumulative FixedClock delta across a fixed
        // number of retries. The stage-based step now computes its schedule through the shared
        // BackoffCalculator with the canonical symmetric jitter (DEFAULT_JITTER = 0.2), so the
        // cumulative elapsed must land within the ±10% jitter window of the canonical series sum.
        for (retries in 1..4) {
            val clock = FixedClock()
            val fake = FakeHttpClient()
            repeat(retries) { fake.enqueue { status(503) } }
            fake.enqueue { status(200) }

            val opts =
                HttpRetryOptions(
                    maxRetries = retries,
                    baseDelay = Duration.ofMillis(BASE_MS),
                    maxDelay = Duration.ofSeconds(1),
                )
            val pipeline =
                HttpPipelineBuilder(fake)
                    .append(DefaultRetryStep(opts, clock))
                    .build()

            val before = clock.now()
            pipeline.send(getRequest())
            val elapsedMs = Duration.between(before, clock.now()).toMillis()

            // Cumulative expected = sum of the first `retries` series entries.
            var sum = 0L
            for (i in 0 until retries) sum += expectedSeriesMs[i]
            // Symmetric ±10% jitter (DEFAULT_JITTER = 0.2 → half-range 10%) per term; bound the sum.
            val tolerance = (sum * (RetrySettings.DEFAULT_JITTER / 2.0)).toLong() + 1
            val low = sum - tolerance
            val high = sum + tolerance
            assertTrue(
                elapsedMs in low..high,
                "retries=$retries: elapsed=$elapsedMs not in [$low,$high] (sum=$sum)",
            )
        }
    }

    // endregion

    // region -- consistent 408 policy --

    @Test
    fun `stage-based step retries a 408 by default`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(408) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 2), zeroDelayClock()))
                .build()
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "stage-based step must retry 408")
    }

    @Test
    fun `recovery-aware default retryable statuses contains 408`() {
        assertTrue(
            RetrySettings.DEFAULT_RETRYABLE_STATUSES.contains(STATUS_REQUEST_TIMEOUT),
            "recovery-aware default must agree with the stage-based 408 stance",
        )
    }

    @Test
    fun `recovery-aware step retries a 408 HttpException by default`() {
        val client =
            object : HttpClient {
                private var calls = 0

                override fun execute(request: Request): Response {
                    calls += 1
                    if (calls == 1) {
                        throw object : HttpException(
                            status = Status.REQUEST_TIMEOUT,
                            headers = Headers.builder().build(),
                            body = null,
                        ) {}
                    }
                    return Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.OK)
                        .headers(Headers.builder().build())
                        .build()
                }
            }
        // No explicit scheduler: with zero delays the step never schedules a deferred wait, and
        // leaving it null routes through RetryStep's process-wide lazy daemon scheduler rather
        // than leaking a caller-owned executor (which RetryStep never shuts down) per run.
        val settings =
            RetrySettings.builder()
                .initialDelay(Duration.ZERO)
                .maxDelay(Duration.ZERO)
                .delayMultiplier(1.0)
                .jitter(0.0)
                .totalTimeout(Duration.ZERO)
                .build()
        val step = RetryStep(client, settings, getRequest())
        val response = step.attempt()
        assertEquals(200, response.status.code, "recovery-aware step must retry a 408")
    }

    // endregion

    // region -- eager delay-magnitude validation --

    @Test
    fun `constructing the stage-based step validates delay magnitudes eagerly`() {
        // HttpRetryOptions does not range-check baseDelay/maxDelay, but routing them through the
        // shared RetrySettings (to drive BackoffCalculator) does. A negative delay must therefore
        // fail fast at DefaultRetryStep construction, not later at delay-computation time.
        assertThrows(IllegalArgumentException::class.java) {
            DefaultRetryStep(
                HttpRetryOptions(baseDelay = Duration.ofMillis(-1)),
                zeroDelayClock(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DefaultRetryStep(
                HttpRetryOptions(maxDelay = Duration.ofMillis(-1)),
                zeroDelayClock(),
            )
        }
    }

    // endregion

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .build()

    private fun zeroDelayClock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.EPOCH

            override fun monotonic(): Long = 0L

            override fun sleep(duration: Duration) = Unit
        }

    private companion object {
        private const val BASE_MS = 100L
        private const val STATUS_REQUEST_TIMEOUT = 408
    }
}
