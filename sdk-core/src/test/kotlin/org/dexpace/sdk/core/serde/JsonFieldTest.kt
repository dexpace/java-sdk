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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonFieldTest {
    // ----- Sentinel toString -----

    @Test
    fun `Missing toString is stable and identity-free`() {
        assertEquals("Missing", JsonField.Missing.toString())
        assertFalse(JsonField.Missing.toString().contains("@"))
    }

    @Test
    fun `Null toString is stable and identity-free`() {
        assertEquals("Null", JsonField.Null.toString())
        assertFalse(JsonField.Null.toString().contains("@"))
    }

    @Test
    fun `Known toString renders the data-class form`() {
        assertEquals("Known(value=hi)", JsonField.Known("hi").toString())
    }

    // ----- predicates -----

    @Test
    fun `predicates report the active variant`() {
        assertTrue(JsonField.Missing.isMissing)
        assertTrue(JsonField.Null.isNull)
        assertTrue(JsonField.Known(1).isKnown)
        assertTrue(JsonField.Raw(RawJson.Str("x")).isRaw)

        val known: JsonField<Int> = JsonField.Known(1)
        assertFalse(known.isMissing)
        assertFalse(known.isNull)
        assertFalse(known.isRaw)
    }

    // ----- factories -----

    @Test
    fun `factories return the expected variants`() {
        assertSame(JsonField.Missing, JsonField.missing<String>())
        assertSame(JsonField.Null, JsonField.nullValue<String>())
        assertEquals(JsonField.Known("v"), JsonField.known("v"))
        assertEquals(JsonField.Raw(RawJson.Null), JsonField.raw(RawJson.Null))
    }

    @Test
    fun `ofNullable routes null to Null and non-null to Known`() {
        assertSame(JsonField.Null, JsonField.ofNullable<String>(null))
        assertEquals(JsonField.Known("x"), JsonField.ofNullable("x"))
    }

    @Test
    fun `Known is never equal to Null or Missing or Raw`() {
        val known: JsonField<String> = JsonField.known("anything")
        assertFalse(known == JsonField.Null)
        assertFalse(known == JsonField.Missing)
        assertFalse(known == JsonField.Raw(RawJson.Str("anything")))
    }

    // ----- getOrNull -----

    @Test
    fun `getOrNull yields value for Known and null for every other variant`() {
        assertEquals("v", JsonField.known("v").getOrNull())
        assertEquals(null, JsonField.Null.getOrNull())
        assertEquals(null, JsonField.Missing.getOrNull())
        assertEquals(null, JsonField.Raw(RawJson.Str("v")).getOrNull())
    }

    // ----- fold -----

    @Test
    fun `fold dispatches to the matching branch`() {
        fun <T> label(f: JsonField<T>): String =
            f.fold(
                onMissing = { "missing" },
                onNull = { "null" },
                onKnown = { "known:$it" },
                onRaw = { "raw:$it" },
            )
        assertEquals("missing", label(JsonField.Missing))
        assertEquals("null", label(JsonField.Null))
        assertEquals("known:7", label(JsonField.known(7)))
        assertEquals("raw:\"x\"", label(JsonField.Raw(RawJson.Str("x"))))
    }

    // ----- Tristate interop -----

    @Test
    fun `fromTristate lifts the three classic states and never produces Raw`() {
        assertSame(JsonField.Missing, JsonField.fromTristate(Tristate.absent<String>()))
        assertSame(JsonField.Null, JsonField.fromTristate(Tristate.nullValue<String>()))
        assertEquals(JsonField.Known("v"), JsonField.fromTristate(Tristate.present("v")))
    }

    @Test
    fun `toTristate maps Missing Null and Known directly`() {
        assertSame(Tristate.Absent, JsonField.Missing.toTristate())
        assertSame(Tristate.Null, JsonField.Null.toTristate())
        assertEquals(Tristate.Present("v"), JsonField.known("v").toTristate())
    }

    @Test
    fun `toTristate is lossy for Raw and folds it into Present of the RawJson`() {
        val raw = RawJson.Arr.of(listOf(RawJson.Num("1")))
        val tristate = JsonField.Raw(raw).toTristate()
        assertEquals(Tristate.Present<Any>(raw), tristate)
    }

    @Test
    fun `round-tripping a clean Tristate through JsonField is lossless`() {
        val states: List<Tristate<String>> =
            listOf(Tristate.absent(), Tristate.nullValue(), Tristate.present("v"))
        for (state in states) {
            val back = JsonField.fromTristate(state).toTristate()
            assertEquals(state, back, "Round-trip changed $state")
        }
    }
}
