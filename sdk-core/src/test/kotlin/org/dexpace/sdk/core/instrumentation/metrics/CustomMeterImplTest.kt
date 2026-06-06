/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation.metrics

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies that the public interfaces can be implemented by an arbitrary caller — i.e.
 * the contracts are usable, not just satisfiable by the noop singletons.
 */
class CustomMeterImplTest {
    private class RecordingCounter : LongCounter {
        val total = AtomicLong(0)
        val calls = mutableListOf<Pair<Long, Map<String, Any>>>()

        override fun add(
            value: Long,
            attributes: Map<String, Any>,
        ) {
            total.addAndGet(value)
            calls += value to attributes
        }
    }

    private class RecordingHistogram : DoubleHistogram {
        val samples = mutableListOf<Pair<Double, Map<String, Any>>>()

        override fun record(
            value: Double,
            attributes: Map<String, Any>,
        ) {
            samples += value to attributes
        }
    }

    private class RecordingMeter : Meter {
        val counter = RecordingCounter()
        val histogram = RecordingHistogram()

        override fun counter(
            name: String,
            description: String,
            unit: String,
        ): LongCounter = counter

        override fun histogram(
            name: String,
            description: String,
            unit: String,
        ): DoubleHistogram = histogram
    }

    @Test
    fun `custom counter records values and attributes`() {
        val meter = RecordingMeter()
        val c = meter.counter("test.counter", "for tests", "{thing}")

        c.add(1L)
        c.add(2L, mapOf("k" to "v"))
        c.add(3L)

        assertEquals(6L, meter.counter.total.get())
        assertEquals(3, meter.counter.calls.size)
        assertEquals(emptyMap(), meter.counter.calls[0].second)
        assertEquals(mapOf("k" to "v"), meter.counter.calls[1].second)
    }

    @Test
    fun `custom histogram records values and attributes`() {
        val meter = RecordingMeter()
        val h = meter.histogram("test.latency", "for tests", "ms")

        h.record(1.0)
        h.record(2.5, mapOf("method" to "GET"))

        assertEquals(2, meter.histogram.samples.size)
        assertEquals(1.0, meter.histogram.samples[0].first)
        assertEquals(emptyMap(), meter.histogram.samples[0].second)
        assertEquals(2.5, meter.histogram.samples[1].first)
        assertEquals(mapOf("method" to "GET"), meter.histogram.samples[1].second)
    }

    @Test
    fun `Meter implementations can be swapped polymorphically`() {
        val meters: List<Meter> = listOf(NoopMeter, RecordingMeter())

        for (m in meters) {
            // Every Meter must produce instruments that accept the documented operations.
            val c = m.counter("n", "d", "u")
            val h = m.histogram("n", "d", "u")
            c.add(1L)
            c.add(2L, mapOf("k" to "v"))
            h.record(1.0)
            h.record(2.0, mapOf("k" to "v"))
        }

        // After the polymorphic loop, the recording meter at index 1 must have observed
        // the two add() and two record() calls from its iteration.
        val recording = meters[1] as RecordingMeter
        assertEquals(2, recording.counter.calls.size)
        assertEquals(2, recording.histogram.samples.size)
    }

    @Test
    fun `default attribute argument resolves to the shared emptyMap singleton`() {
        // Kotlin's `emptyMap()` returns the same instance every time; confirm the
        // default-argument call path doesn't allocate a fresh map per call.
        val seen = mutableListOf<Map<String, Any>>()
        val c =
            object : LongCounter {
                override fun add(
                    value: Long,
                    attributes: Map<String, Any>,
                ) {
                    seen += attributes
                }
            }
        c.add(1L)
        c.add(2L)
        assertSame(seen[0], seen[1], "Default attributes must be the shared emptyMap singleton")
        assertSame(emptyMap<String, Any>(), seen[0])
    }
}
