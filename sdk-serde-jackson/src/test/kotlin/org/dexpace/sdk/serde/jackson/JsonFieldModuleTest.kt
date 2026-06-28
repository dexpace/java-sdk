/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dexpace.sdk.core.serde.JsonField
import org.dexpace.sdk.core.serde.RawJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonFieldModuleTest {
    data class Holder(
        val x: JsonField<String> = JsonField.Missing,
    )

    data class IntHolder(val n: JsonField<Int> = JsonField.Missing)

    data class Inner(val k: String)

    data class NestedHolder(val inner: JsonField<Inner> = JsonField.Missing)

    private fun mapper(): ObjectMapper = JacksonObjectMappers.defaultObjectMapper()

    // ----- Deserialization: the four states -----

    @Test
    fun `empty object deserializes the field as Missing`() {
        val parsed: Holder = mapper().readValue("""{}""")
        assertTrue(parsed.x is JsonField.Missing, "Expected Missing, got ${parsed.x}")
        assertTrue(parsed.x.isMissing)
    }

    @Test
    fun `explicit null deserializes the field as Null`() {
        val parsed: Holder = mapper().readValue("""{"x": null}""")
        assertTrue(parsed.x is JsonField.Null, "Expected Null, got ${parsed.x}")
    }

    @Test
    fun `matching value deserializes the field as Known`() {
        val parsed: Holder = mapper().readValue("""{"x": "value"}""")
        assertEquals(JsonField.Known("value"), parsed.x)
    }

    @Test
    fun `wrong-typed value deserializes the field as Raw rather than failing`() {
        // n is declared JsonField<Int>, but the wire sent an object — must not throw, must capture Raw.
        val parsed: IntHolder = mapper().readValue("""{"n": {"unexpected": [1, 2]}}""")
        val field = parsed.n
        assertTrue(field is JsonField.Raw, "Expected Raw, got $field")
        val raw = field.json
        assertTrue(raw is RawJson.Obj)
        val inner = raw["unexpected"]
        assertTrue(inner is RawJson.Arr)
    }

    @Test
    fun `Known with parametric inner type deserializes correctly`() {
        val parsed: IntHolder = mapper().readValue("""{"n": 42}""")
        assertEquals(JsonField.Known(42), parsed.n)
    }

    @Test
    fun `Known with nested DTO inner type deserializes correctly`() {
        val parsed: NestedHolder = mapper().readValue("""{"inner": {"k": "value"}}""")
        assertEquals(JsonField.Known(Inner("value")), parsed.inner)
    }

    // ----- Serialization: the four states -----

    @Test
    fun `Missing on serialize omits the field`() {
        val json = mapper().writeValueAsString(Holder(x = JsonField.Missing))
        assertEquals("""{}""", json)
        assertFalse(json.contains("\"x\""))
    }

    @Test
    fun `Null on serialize writes JSON null`() {
        val json = mapper().writeValueAsString(Holder(x = JsonField.Null))
        assertEquals("""{"x":null}""", json)
    }

    @Test
    fun `Known on serialize writes the inner value`() {
        val json = mapper().writeValueAsString(Holder(x = JsonField.Known("hello")))
        assertEquals("""{"x":"hello"}""", json)
    }

    @Test
    fun `Raw on serialize emits the captured JSON verbatim`() {
        val raw =
            RawJson.Obj.of(
                linkedMapOf(
                    "a" to RawJson.Num("9007199254740993"),
                    "b" to RawJson.Arr.of(listOf(RawJson.Bool.TRUE, RawJson.Null)),
                ),
            )
        val json = mapper().writeValueAsString(IntHolder(n = JsonField.Raw(raw)))
        assertEquals("""{"n":{"a":9007199254740993,"b":[true,null]}}""", json)
    }

    // ----- Round-trips -----

    @Test
    fun `round-trip preserves all four states for a string field`() {
        val m = mapper()
        val states: List<JsonField<String>> =
            listOf(
                JsonField.Missing,
                JsonField.Null,
                JsonField.Known("v"),
                JsonField.Raw(RawJson.Num("12")),
            )
        for (state in states) {
            val original = Holder(x = state)
            val asString = m.writeValueAsString(original)
            val back: Holder = m.readValue(asString)
            // Note: a Raw whose payload happens to match T binds back to Known on re-read; we test
            // the genuinely-unbindable Raw separately. Here Raw(Num) into JsonField<String> stays Raw.
            assertEquals(original, back, "Round-trip failed for $state via $asString")
        }
    }

    @Test
    fun `a large long id survives a wrong-type Raw round-trip without precision loss`() {
        val m = mapper()
        // Field is JsonField<String>; the wire sends a huge number → Raw(Num) preserving the literal.
        val parsed: Holder = m.readValue("""{"x": 9007199254740993}""")
        assertEquals(JsonField.Raw(RawJson.Num("9007199254740993")), parsed.x)
        val reEmitted = m.writeValueAsString(parsed)
        assertEquals("""{"x":9007199254740993}""", reEmitted)
    }

    // ----- bare RawJson serialization -----

    @Test
    fun `a bare RawJson value serializes to real JSON`() {
        val tree =
            RawJson.Obj.of(
                linkedMapOf(
                    "s" to RawJson.Str("x"),
                    "n" to RawJson.Num("1.5"),
                    "arr" to RawJson.Arr.of(listOf(RawJson.Bool.FALSE)),
                ),
            )
        assertEquals("""{"s":"x","n":1.5,"arr":[false]}""", mapper().writeValueAsString(tree))
    }
}
