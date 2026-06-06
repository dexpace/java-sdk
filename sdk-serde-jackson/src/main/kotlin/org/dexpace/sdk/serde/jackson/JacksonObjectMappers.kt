/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * Factory for the SDK's default [ObjectMapper] configuration.
 *
 * The default mapper is configured with **SDK-correct** flags as established by reference SDKs
 * (Square in particular — see `ObjectMappers.java:21-22` in their codebase) and supplemented by
 * a Tristate module for `PATCH` semantics:
 *
 *  - [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] **disabled** — server payloads commonly
 *    add new fields ahead of client model bumps; treating that as an error would force
 *    every consumer to ship a new SDK release on each backward-compatible server change.
 *  - [SerializationFeature.WRITE_DATES_AS_TIMESTAMPS] **disabled** — emits ISO-8601 strings
 *    rather than numeric epoch millis, matching every public REST API.
 *
 * Modules registered, in order:
 *  - [KotlinModule]    — Kotlin data classes, default-argument support, value classes.
 *  - [JavaTimeModule]  — `java.time.*` (Instant, OffsetDateTime, LocalDate, ...).
 *  - [Jdk8Module]      — `Optional<T>`, `OptionalInt/Long/Double`, parameter-name introspection.
 *  - [TristateModule]  — `Tristate<T>` (Absent / Null / Present) ser/de.
 *
 * Each call to [defaultObjectMapper] returns a **fresh** mapper instance; sharing or mutating a
 * cached singleton would be a foot-gun (mappers carry mutable caches that interact poorly with
 * post-construction reconfiguration). Callers that want a single mapper across their app should
 * cache the return value at their own boundary.
 */
public object JacksonObjectMappers {
    /**
     * Build a new [ObjectMapper] pre-wired with the SDK defaults. The returned mapper is fully
     * configured and ready to use — callers may further chain mapper-mutating calls (`registerModule`,
     * `enable`/`disable` features) but should treat the result as their own instance to mutate.
     */
    public fun defaultObjectMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        mapper.registerModule(KotlinModule.Builder().build())
        mapper.registerModule(JavaTimeModule())
        mapper.registerModule(Jdk8Module())
        mapper.registerModule(TristateModule())
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // Caller-owned streams must not be closed by the mapper. Both write and read paths
        // need this — `Serializer.serialize(input, OutputStream)` and
        // `Deserializer.deserialize(InputStream)` explicitly document caller ownership.
        mapper.factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        mapper.factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
        return mapper
    }
}
