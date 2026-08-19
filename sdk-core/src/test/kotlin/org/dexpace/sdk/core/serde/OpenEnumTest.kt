/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenEnumTest {
    /**
     * Hand-written concrete enum exercising the [OpenEnum] base exactly as a generated type would:
     * a closed [Known] enum, a parallel [Value] enum with an `UNKNOWN` sentinel, and a private
     * constructor with `of`-style factories plus public constants.
     */
    private class ReasoningEffort private constructor(
        rawValue: String,
    ) : OpenEnum<ReasoningEffort.Known, ReasoningEffort.Value>(rawValue) {
        enum class Known(val wireValue: String) {
            LOW("low"),
            MEDIUM("medium"),
            HIGH("high"),
        }

        enum class Value {
            LOW,
            MEDIUM,
            HIGH,
            UNKNOWN,
        }

        override fun knownToValue(known: Known): Value = Value.valueOf(known.name)

        override fun resolveKnown(rawValue: String): Known? = Known.entries.firstOrNull { it.wireValue == rawValue }

        override fun unknownValue(): Value = Value.UNKNOWN

        companion object {
            val LOW: ReasoningEffort = ReasoningEffort("low")
            val HIGH: ReasoningEffort = ReasoningEffort("high")

            fun of(value: String): ReasoningEffort = ReasoningEffort(value)
        }
    }

    @Test
    fun `known value resolves to its Known variant`() {
        assertEquals(ReasoningEffort.Known.LOW, ReasoningEffort.LOW.known())
        assertEquals(ReasoningEffort.Known.HIGH, ReasoningEffort.of("high").known())
    }

    @Test
    fun `value() returns the mirrored Value for known inputs`() {
        assertEquals(ReasoningEffort.Value.LOW, ReasoningEffort.of("low").value())
        assertEquals(ReasoningEffort.Value.MEDIUM, ReasoningEffort.of("medium").value())
    }

    @Test
    fun `unknown server value deserializes without throwing and round-trips its raw string`() {
        val parsed = ReasoningEffort.of("ultra")

        // The point of the primitive: no throw on an unrecognised value.
        assertTrue(parsed.isUnknown)
        assertEquals(ReasoningEffort.Value.UNKNOWN, parsed.value())
        assertNull(parsed.knownOrNull())
        // Raw string preserved so it can be re-serialized unchanged.
        assertEquals("ultra", parsed.rawValue)
        assertEquals("ultra", parsed.toString())
    }

    @Test
    fun `known() throws on an unknown value`() {
        try {
            ReasoningEffort.of("ultra").known()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ultra"))
        }
    }

    @Test
    fun `isUnknown is false for recognised values`() {
        assertFalse(ReasoningEffort.LOW.isUnknown)
        assertEquals(ReasoningEffort.Known.LOW, ReasoningEffort.LOW.knownOrNull())
    }

    @Test
    fun `equality and hashCode follow the raw value, not subclass identity`() {
        assertEquals(ReasoningEffort.LOW, ReasoningEffort.of("low"))
        assertEquals(ReasoningEffort.LOW.hashCode(), ReasoningEffort.of("low").hashCode())
        assertFalse(ReasoningEffort.LOW == ReasoningEffort.HIGH)
    }

    @Test
    fun `unknown values compare equal when their raw strings match`() {
        assertEquals(ReasoningEffort.of("ultra"), ReasoningEffort.of("ultra"))
        assertFalse(ReasoningEffort.of("ultra") == ReasoningEffort.of("mega"))
    }
}
