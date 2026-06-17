/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dexpace.sdk.core.serde.AdditionalProperties
import org.dexpace.sdk.core.serde.RawJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demonstrates the `additionalProperties` pass-through primitive (issue #52): a model with one
 * typed field captures every *unknown* key into an [AdditionalProperties] snapshot at
 * deserialization and re-emits it on serialization, so a read-modify-write loop never silently
 * drops a server-added field.
 *
 * The DTO below is hand-written but is exactly the shape a generator would emit: a Jackson
 * `@JsonAnySetter` funnels unknown keys (as [RawJson] via the [RawJson] converter) into a builder,
 * and a `@JsonAnyGetter` re-exposes them. The snapshot itself lives in `sdk-core` with no Jackson
 * dependency.
 */
class AdditionalPropertiesPassThroughTest {
    /**
     * A model with one known field (`name`) and an [AdditionalProperties] bucket for everything else.
     * The any-setter captures unknown keys; the any-getter re-emits them. The bucket is built
     * lazily from a mutable builder while Jackson populates the bean, then frozen via [build].
     */
    class Model {
        var name: String? = null

        @field:JsonIgnore
        private val extras: AdditionalProperties.Builder = AdditionalProperties.builder()

        @JsonAnySetter
        fun capture(
            key: String,
            value: JsonNode,
        ) {
            extras.put(key, RawJsonConversions.fromNode(value))
        }

        @get:JsonAnyGetter
        val additionalProperties: Map<String, RawJson>
            get() = build().entries

        @JsonIgnore
        fun build(): AdditionalProperties = extras.build()
    }

    private fun mapper(): ObjectMapper = JacksonObjectMappers.defaultObjectMapper()

    @Test
    fun `unknown fields are captured into an AdditionalProperties snapshot`() {
        val m = mapper()
        val model: Model =
            m.readValue(
                """{"name":"widget","count":7,"tags":["a","b"],"nested":{"k":true}}""",
            )
        assertEquals("widget", model.name)

        val extras = model.build()
        assertEquals(setOf("count", "tags", "nested"), extras.keys)
        assertEquals(RawJson.Num("7"), extras["count"])
        assertEquals(RawJson.Arr.of(listOf(RawJson.Str("a"), RawJson.Str("b"))), extras["tags"])
        assertTrue(extras["nested"] is RawJson.Obj)
    }

    @Test
    fun `unknown fields survive a read-modify-write round-trip losslessly`() {
        val m = mapper()
        val source = """{"name":"widget","serverAddedId":9007199254740993,"flag":true}"""
        val model: Model = m.readValue(source)

        // Modify a known field; leave the unknown ones untouched.
        model.name = "renamed"

        val written = m.writeValueAsString(model)
        // The server-added fields — including the large id that a Double round-trip would corrupt —
        // are preserved verbatim, and the modified known field is reflected.
        val reparsed: Map<String, Any?> = m.readValue(written)
        assertEquals("renamed", reparsed["name"])
        assertEquals(9007199254740993L, reparsed["serverAddedId"])
        assertEquals(true, reparsed["flag"])
    }

    @Test
    fun `a model with no unknown fields emits nothing extra`() {
        val m = mapper()
        val model: Model = m.readValue("""{"name":"only"}""")
        assertTrue(model.build().isEmpty())
        assertEquals("""{"name":"only"}""", m.writeValueAsString(model))
    }
}
