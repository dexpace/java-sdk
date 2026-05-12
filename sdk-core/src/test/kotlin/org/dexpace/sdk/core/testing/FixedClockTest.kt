package org.dexpace.sdk.core.testing

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedClockTest {

    @Test
    fun `now returns the constructor-supplied instant`() {
        val origin = Instant.parse("2024-01-01T00:00:00Z")
        val clock = FixedClock(origin)
        assertEquals(origin, clock.now())
    }

    @Test
    fun `advance moves now forward by the requested duration`() {
        val clock = FixedClock(Instant.EPOCH)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(Instant.EPOCH.plus(Duration.ofMinutes(5)), clock.now())
    }

    @Test
    fun `sleep advances current without real blocking`() {
        val clock = FixedClock(Instant.EPOCH)
        val realStart = System.nanoTime()

        clock.sleep(Duration.ofMinutes(1))

        val realElapsedMillis = (System.nanoTime() - realStart) / 1_000_000
        assertTrue(realElapsedMillis < 50, "Expected <50ms wall time, got $realElapsedMillis")
        assertEquals(Instant.EPOCH.plus(Duration.ofMinutes(1)), clock.now())
    }

    @Test
    fun `sleep with negative duration throws IllegalArgumentException`() {
        val clock = FixedClock()
        assertFailsWith<IllegalArgumentException> {
            clock.sleep(Duration.ofMillis(-1))
        }
    }

    @Test
    fun `advance with negative duration throws IllegalArgumentException`() {
        val clock = FixedClock()
        assertFailsWith<IllegalArgumentException> {
            clock.advance(Duration.ofMillis(-1))
        }
    }

    @Test
    fun `monotonic is non-decreasing across advance calls`() {
        val clock = FixedClock(Instant.EPOCH)
        val m0 = clock.monotonic()
        clock.advance(Duration.ofMillis(10))
        val m1 = clock.monotonic()
        clock.sleep(Duration.ofMillis(20))
        val m2 = clock.monotonic()

        assertTrue(m1 >= m0, "monotonic regressed: $m0 -> $m1")
        assertTrue(m2 >= m1, "monotonic regressed: $m1 -> $m2")
    }

    @Test
    fun `default constructor uses Instant EPOCH`() {
        val clock = FixedClock()
        assertEquals(Instant.EPOCH, clock.now())
    }
}
