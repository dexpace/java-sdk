/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.util.Clock
import java.time.Duration
import java.time.Instant

/**
 * Test [Clock] that advances time only when [advance] is called or [sleep] is invoked.
 *
 * Replaces real `Thread.sleep` in tests of time-dependent code (retry back-off, token
 * expiry). Pair with [FakeHttpClient]: a step that calls `clock.sleep(d)` advances the
 * clock instantly, the test then asserts on `clock.now()` to verify the requested delay.
 *
 * Not thread-safe: tests run on a single thread.
 */
class FixedClock
    @JvmOverloads
    constructor(
        /** The current instant returned by [now]. Mutated by [advance] and [sleep]. */
        var current: Instant = Instant.EPOCH,
    ) : Clock {
        /** Captured at construction so [monotonic] can report nanos elapsed from `current`. */
        private val start: Instant = current

        override fun now(): Instant = current

        /**
         * Returns the number of nanoseconds elapsed between the clock's construction-time
         * `current` and the present `current`. Monotonically non-decreasing while [advance]
         * and [sleep] only move `current` forward (the contract — see those methods).
         */
        override fun monotonic(): Long = Duration.between(start, current).toNanos()

        /**
         * Advances [current] by [duration] without blocking the calling thread. Negative
         * durations are rejected to match [Clock.SYSTEM] semantics.
         */
        override fun sleep(duration: Duration) {
            require(!duration.isNegative) { "duration must be non-negative (got $duration)" }
            current = current.plus(duration)
        }

        /**
         * Advances [current] by [duration]. Helper for tests that want to move time forward
         * without expressing it as a "sleep". Rejects negative durations.
         */
        fun advance(duration: Duration) {
            require(!duration.isNegative) { "duration must be non-negative (got $duration)" }
            current = current.plus(duration)
        }
    }
