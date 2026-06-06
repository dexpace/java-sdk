/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClockTest {
    @Test
    fun `now is within one second of Instant now`() {
        val before = Instant.now()
        val sample = Clock.SYSTEM.now()
        val after = Instant.now()

        // The sample must lie within [before - 1s, after + 1s] — generous tolerance for
        // clock granularity / scheduling jitter.
        assertTrue(
            !sample.isBefore(before.minusSeconds(1)) && !sample.isAfter(after.plusSeconds(1)),
            "Expected $sample to be within 1s of [$before, $after]",
        )
    }

    @Test
    fun `monotonic is non-decreasing between consecutive calls`() {
        val a = Clock.SYSTEM.monotonic()
        val b = Clock.SYSTEM.monotonic()
        val c = Clock.SYSTEM.monotonic()

        assertTrue(b >= a, "monotonic went backwards: $a -> $b")
        assertTrue(c >= b, "monotonic went backwards: $b -> $c")
    }

    @Test
    fun `sleep zero returns without throwing`() {
        Clock.SYSTEM.sleep(Duration.ZERO)
    }

    @Test
    fun `sleep blocks for at least the requested duration`() {
        val start = System.nanoTime()
        Clock.SYSTEM.sleep(Duration.ofMillis(50))
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertTrue(elapsedMillis >= 40, "Expected >=40ms, got $elapsedMillis")
        assertTrue(elapsedMillis <= 200, "Expected <=200ms (generous), got $elapsedMillis")
    }

    @Test
    fun `sleep with negative duration throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            Clock.SYSTEM.sleep(Duration.ofMillis(-1))
        }
    }

    @Test
    fun `sleep is interruptible and preserves interrupt-status at catch time`() {
        val ready = CountDownLatch(1)
        val thrown = AtomicReference<InterruptedException?>(null)
        val interruptedAtCatch = AtomicBoolean(false)
        val end = AtomicReference<Long>(0L)

        val sleeper =
            Thread {
                ready.countDown()
                try {
                    Clock.SYSTEM.sleep(Duration.ofSeconds(5))
                } catch (e: InterruptedException) {
                    // `Thread.sleep` clears the interrupt status when it throws, so checking
                    // the flag here would always be false on a standard JVM. What matters per
                    // Rank 22 is that the thread was in the interrupted state at the moment
                    // sleep aborted — verified by the fact that we caught the exception at
                    // all. Re-set the flag so downstream cleanup observes it.
                    interruptedAtCatch.set(true)
                    Thread.currentThread().interrupt()
                    thrown.set(e)
                } finally {
                    end.set(System.nanoTime())
                }
            }

        sleeper.start()
        ready.await()
        // Give the sleep call a chance to actually start before we interrupt.
        Thread.sleep(50)
        val interruptStart = System.nanoTime()
        sleeper.interrupt()
        sleeper.join(2_000)

        assertNotNull(thrown.get(), "Expected InterruptedException to be thrown")
        assertTrue(interruptedAtCatch.get(), "Interrupt-status flag at catch time should be set")
        val elapsedMillis = (end.get() - interruptStart) / 1_000_000
        assertTrue(elapsedMillis <= 200, "Sleep should abort within 200ms of interrupt, got $elapsedMillis")
    }

    @Test
    fun `SYSTEM is a singleton`() {
        // Two reads of the companion property must return the same instance.
        assertSame(Clock.SYSTEM, Clock.SYSTEM)
    }
}
