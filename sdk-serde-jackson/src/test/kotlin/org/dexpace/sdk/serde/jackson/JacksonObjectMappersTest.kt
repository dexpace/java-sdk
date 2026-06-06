/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JacksonObjectMappersTest {
    data class Simple(val name: String, val count: Int)

    data class WithInstant(val occurredAt: Instant)

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
}
