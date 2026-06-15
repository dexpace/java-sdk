/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JacksonObjectMappersTest {
    data class Simple(val name: String, val count: Int)

    data class WithInstant(val occurredAt: Instant)

    data class Numbers(val count: Int, val ratio: Double)

    data class Flag(val enabled: Boolean)

    data class Label(val text: String)

    @Test
    fun `default mapper deserializes JSON with extra field successfully`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        // The DTO has fields name+count; the JSON adds an unknown 'extra'. FAIL_ON_UNKNOWN=false
        // must allow this and yield a well-formed Simple.
        val json = """{"name":"alice","count":3,"extra":"ignored"}"""
        val parsed: Simple = mapper.readValue(json)
        assertEquals(Simple("alice", 3), parsed)
    }

    @Test
    fun `ISO-8601 instant round-trip not numeric timestamp`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        val moment = Instant.parse("2026-01-15T10:30:00Z")
        val serialized = mapper.writeValueAsString(WithInstant(moment))
        // WRITE_DATES_AS_TIMESTAMPS=false → string form, not a number.
        assertTrue(
            serialized.contains("\"2026-01-15T10:30:00Z\""),
            "Expected ISO-8601 string form, got: $serialized",
        )
        val parsed: WithInstant = mapper.readValue(serialized)
        assertEquals(moment, parsed.occurredAt)
    }

    @Test
    fun `default mapper is a fresh instance each call`() {
        val a = JacksonObjectMappers.defaultObjectMapper()
        val b = JacksonObjectMappers.defaultObjectMapper()
        // Distinct mappers — caller-owned, no shared mutable state.
        assertTrue(a !== b)
    }

    // ----- Coercion lockdown (#24): mismatched scalar shapes must fail, not coerce silently -----

    @Test
    fun `string for an integer field is rejected, not coerced`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        // The headline case: a wire string "5" must NOT slip into a numeric field.
        assertFailsWith<MismatchedInputException> {
            mapper.readValue<Numbers>("""{"count":"5","ratio":1.5}""")
        }
    }

    @Test
    fun `string for a floating-point field is rejected, not coerced`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        assertFailsWith<MismatchedInputException> {
            mapper.readValue<Numbers>("""{"count":5,"ratio":"1.5"}""")
        }
    }

    @Test
    fun `string for a boolean field is rejected, not coerced`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        assertFailsWith<MismatchedInputException> {
            mapper.readValue<Flag>("""{"enabled":"true"}""")
        }
    }

    @Test
    fun `number for a string field is rejected, not coerced`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        assertFailsWith<MismatchedInputException> {
            mapper.readValue<Label>("""{"text":5}""")
        }
    }

    @Test
    fun `boolean for a string field is rejected, not coerced`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        assertFailsWith<MismatchedInputException> {
            mapper.readValue<Label>("""{"text":true}""")
        }
    }

    @Test
    fun `genuine numeric, floating-point, boolean and string values still bind`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        // The lockdown must not break correctly-typed payloads.
        assertEquals(Numbers(5, 1.5), mapper.readValue<Numbers>("""{"count":5,"ratio":1.5}"""))
        assertEquals(Flag(true), mapper.readValue<Flag>("""{"enabled":true}"""))
        assertEquals(Label("hi"), mapper.readValue<Label>("""{"text":"hi"}"""))
    }

    @Test
    fun `integer for a floating-point field still widens`() {
        val mapper = JacksonObjectMappers.defaultObjectMapper()
        // int -> double is a numeric widening, not a cross-shape coercion: it must keep working.
        assertEquals(Numbers(1, 2.0), mapper.readValue<Numbers>("""{"count":1,"ratio":2}"""))
    }
}
