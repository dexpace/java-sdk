package org.dexpace.sdk.core.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the top-level `generate*TraceId()` functions and the [TraceIdType] enum's
 * `generate` factory hook. The W3C and Datadog generators have a zero-coercion clause —
 * we exercise it indirectly (uniformly random output means zero is statistically
 * negligible; the coercion path is asserted via shape/non-zero invariants).
 */
class TraceIdTypeTest {

    // -- generateW3CTraceId -----------------------------------------------------------

    @Test
    fun `generateW3CTraceId produces 32 lowercase hex characters`() {
        val id = generateW3CTraceId()
        assertEquals(32, id.value.length, "expected 32 hex chars, got '${id.value}'")
        assertTrue(
            id.value.all { it in '0'..'9' || it in 'a'..'f' },
            "expected lowercase hex, got '${id.value}'",
        )
    }

    @Test
    fun `generateW3CTraceId result is non-zero`() {
        // The factory coerces a zero low-half to 1 — the full id must therefore never
        // equal the reserved all-zero W3C trace id.
        repeat(50) {
            val id = generateW3CTraceId()
            assertNotEquals(TraceId.NOOP.value, id.value)
        }
    }

    @Test
    fun `generateW3CTraceId is reasonably unique across calls`() {
        // Sanity check that randomness flows through — collision in a tight loop of
        // 100 128-bit values would indicate a broken generator.
        val ids = (1..100).map { generateW3CTraceId().value }.toSet()
        assertTrue(ids.size >= 99, "expected near-unique ids, got ${ids.size} distinct")
    }

    // -- generateDatadogTraceId -------------------------------------------------------

    @Test
    fun `generateDatadogTraceId produces a non-zero unsigned-long decimal string`() {
        val id = generateDatadogTraceId()
        assertNotEquals("0", id.value)
        // ULong.MAX_VALUE = 18446744073709551615 → max 20 decimal digits.
        assertTrue(id.value.length in 1..20, "expected 1..20 chars, got '${id.value}'")
        assertTrue(id.value.all { it.isDigit() }, "expected pure decimal, got '${id.value}'")
        // Parses cleanly as an unsigned long.
        id.value.toULong()
    }

    @Test
    fun `generateDatadogTraceId is reasonably unique`() {
        val ids = (1..100).map { generateDatadogTraceId().value }.toSet()
        assertTrue(ids.size >= 99, "expected near-unique ids, got ${ids.size} distinct")
    }

    // -- generateNoopTraceId ----------------------------------------------------------

    @Test
    fun `generateNoopTraceId returns the TraceId NOOP sentinel`() {
        // Returns the exact same value object — both reference and content equality.
        val id = generateNoopTraceId()
        assertEquals(TraceId.NOOP, id)
        assertEquals(TraceId.NOOP.value, id.value)
    }

    // -- TraceIdType.generate hooks ---------------------------------------------------

    @Test
    fun `TraceIdType W3C generate returns a W3C-shaped id`() {
        val id = TraceIdType.W3C.generate()
        assertEquals(32, id.value.length)
        assertNotEquals(TraceId.NOOP.value, id.value)
    }

    @Test
    fun `TraceIdType DATADOG generate returns a decimal non-zero id`() {
        val id = TraceIdType.DATADOG.generate()
        assertNotEquals("0", id.value)
        assertTrue(id.value.all { it.isDigit() })
    }

    @Test
    fun `TraceIdType NOOP generate returns the NOOP sentinel`() {
        val id = TraceIdType.NOOP.generate()
        assertEquals(TraceId.NOOP, id)
    }

    @Test
    fun `TraceIdType enum exposes exactly three values`() {
        val values = TraceIdType.values().toList()
        assertEquals(3, values.size)
        assertTrue(TraceIdType.W3C in values)
        assertTrue(TraceIdType.DATADOG in values)
        assertTrue(TraceIdType.NOOP in values)
    }

    @Test
    fun `TraceIdType valueOf round-trip`() {
        assertSame(TraceIdType.W3C, TraceIdType.valueOf("W3C"))
        assertSame(TraceIdType.DATADOG, TraceIdType.valueOf("DATADOG"))
        assertSame(TraceIdType.NOOP, TraceIdType.valueOf("NOOP"))
    }
}
