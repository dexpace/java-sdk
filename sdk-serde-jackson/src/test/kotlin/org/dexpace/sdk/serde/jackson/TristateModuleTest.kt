/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dexpace.sdk.core.serde.Tristate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TristateModuleTest {
    /**
     * Test holder class. Field defaults to [Tristate.Absent] so that JSON keys missing entirely
     * surface as Absent rather than collapsing to null via Jackson's missing-property handling.
     */
    data class Holder(
        val x: Tristate<String> = Tristate.Absent,
    )

    private fun mapper(): ObjectMapper = JacksonObjectMappers.defaultObjectMapper()

    // ----- Deserialization -----

    @Test
    fun `empty object deserializes Tristate field as Absent`() {
        val parsed: Holder = mapper().readValue("""{}""")
        assertTrue(parsed.x is Tristate.Absent, "Expected Absent, got ${parsed.x}")
        assertTrue(parsed.x.isAbsent)
        assertFalse(parsed.x.isNull)
        assertFalse(parsed.x.isPresent)
    }

    @Test
    fun `explicit null deserializes Tristate field as Null`() {
        val parsed: Holder = mapper().readValue("""{"x": null}""")
        assertTrue(parsed.x is Tristate.Null, "Expected Null, got ${parsed.x}")
        assertTrue(parsed.x.isNull)
        assertFalse(parsed.x.isAbsent)
        assertFalse(parsed.x.isPresent)
    }

    @Test
    fun `string value deserializes Tristate field as Present`() {
        val parsed: Holder = mapper().readValue("""{"x": "value"}""")
        assertEquals(Tristate.Present("value"), parsed.x)
        assertTrue(parsed.x.isPresent)
        assertFalse(parsed.x.isAbsent)
        assertFalse(parsed.x.isNull)
    }

    // ----- Serialization -----

    @Test
    fun `Absent on serialize omits the field from output`() {
        val json = mapper().writeValueAsString(Holder(x = Tristate.Absent))
        // Stripped of insignificant whitespace, the JSON object should be empty (no `x` key).
        assertEquals("""{}""", json)
        assertFalse(json.contains("\"x\""))
    }

    @Test
    fun `Null on serialize writes JSON null`() {
        val json = mapper().writeValueAsString(Holder(x = Tristate.Null))
        assertTrue(json.contains("\"x\""), "Field name must be present: $json")
        assertTrue(json.contains("null"), "Value must be JSON null: $json")
        // Defensive equality check — exact form should be `{"x":null}`.
        assertEquals("""{"x":null}""", json)
    }

    @Test
    fun `Present on serialize writes the inner value`() {
        val json = mapper().writeValueAsString(Holder(x = Tristate.Present("hello")))
        assertEquals("""{"x":"hello"}""", json)
    }

    // ----- Round-trip combinations -----

    @Test
    fun `round-trip preserves Absent Null and Present`() {
        val m = mapper()
        val states: List<Tristate<String>> = listOf(Tristate.Absent, Tristate.Null, Tristate.Present("v"))
        for (state in states) {
            val original = Holder(x = state)
            val asString = m.writeValueAsString(original)
            val back: Holder = m.readValue(asString)
            assertEquals(original, back, "Round-trip failed for $state via $asString")
        }
    }

    // ----- Generic-parameter respect -----

    @Test
    fun `Present with parametric inner type deserializes correctly`() {
        val parsed: IntHolder = mapper().readValue("""{"n": 42}""")
        assertEquals(Tristate.Present(42), parsed.n)
    }

    @Test
    fun `Present with nested DTO inner type deserializes correctly`() {
        val parsed: NestedHolder = mapper().readValue("""{"inner": {"k": "value"}}""")
        assertEquals(Tristate.Present(Inner("value")), parsed.inner)
    }

    @Test
    fun `Tristate-typed deserialization via TypeReference works at the top level`() {
        // Per the module KDoc, top-level Tristate without a wrapping bean does not benefit from
        // the property-writer hook; this test exercises only the deserializer path.
        val m = mapper()
        val absent: Tristate<String> =
            m.readValue("""null""", object : TypeReference<Tristate<String>>() {})
        // A top-level JSON `null` is unambiguously Null (no field to omit), not Absent.
        assertTrue(absent is Tristate.Null)

        val present: Tristate<String> =
            m.readValue(""""hello"""", object : TypeReference<Tristate<String>>() {})
        assertEquals(Tristate.Present("hello"), present)
    }

    data class Inner(val k: String)

    data class NestedHolder(val inner: Tristate<Inner> = Tristate.Absent)

    data class IntHolder(val n: Tristate<Int> = Tristate.Absent)
}
