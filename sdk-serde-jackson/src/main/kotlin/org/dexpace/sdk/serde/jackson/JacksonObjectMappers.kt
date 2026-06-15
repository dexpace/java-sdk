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
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.cfg.MutableCoercionConfig
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.LogicalType
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
 *  - [MapperFeature.ALLOW_COERCION_OF_SCALARS] **disabled** plus per-type [CoercionAction.Fail]
 *    rules — Jackson's defaults silently reshape mismatched scalars (a wire string `"5"` becomes a
 *    numeric field, a number becomes a string, and so on), which masks malformed payloads. The SDK
 *    rejects those cross-shape coercions so a contract violation surfaces as a failure rather than a
 *    quietly wrong value. Numeric widening (an integer into a floating-point field) and genuinely
 *    typed values are unaffected. See [lockDownScalarCoercion]. Auto-detection features
 *    (`AUTO_DETECT_*`) are deliberately left at their defaults: Kotlin data-class binding relies on
 *    them, and any further tightening belongs to codegen, which owns its own mapper.
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
        // Built via JsonMapper.builder() so the MapperFeature toggle and coercion config are set
        // at build time — the runtime ObjectMapper.configure(MapperFeature, ...) setter is
        // deprecated in 2.18, and withCoercionConfig is the supported coercion entry point.
        val builder =
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .addModule(JavaTimeModule())
                .addModule(Jdk8Module())
                .addModule(TristateModule())
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        applyStrictScalarCoercion(builder)
        val mapper = builder.build()
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // Caller-owned streams must not be closed by the mapper. Both write and read paths
        // need this — `Serializer.serialize(input, OutputStream)` and
        // `Deserializer.deserialize(InputStream)` explicitly document caller ownership.
        mapper.factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        mapper.factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
        return mapper
    }

    /**
     * Reject the lenient cross-shape scalar coercions Jackson enables by default, so a payload
     * whose JSON shape does not match the target type fails loudly instead of being silently
     * reshaped into a wrong value.
     *
     * This is the second half of the lockdown (the first being [MapperFeature.ALLOW_COERCION_OF_SCALARS]
     * disabled by the caller): per-[LogicalType] [CoercionAction.Fail] rules for the specific
     * cross-shape pairs, applied through [com.fasterxml.jackson.databind.cfg.MapperBuilder.withCoercionConfig]
     * so the rejection holds regardless of how a given scalar target consults coercion config:
     *
     *  - string → integer / floating-point / boolean (the headline `"5"` → numeric case),
     *  - boolean ↔ integer,
     *  - integer / floating-point / boolean → string.
     *
     * Untouched on purpose: numeric **widening** (an integer JSON value into a floating-point
     * field) stays legal because it is a representation-preserving conversion, not a shape mismatch;
     * and genuinely typed values bind exactly as before.
     */
    private fun applyStrictScalarCoercion(builder: JsonMapper.Builder) {
        fun JsonMapper.Builder.failOn(
            target: LogicalType,
            vararg shapes: CoercionInputShape,
        ): JsonMapper.Builder =
            withCoercionConfig(target) { cfg: MutableCoercionConfig ->
                shapes.forEach { shape -> cfg.setCoercion(shape, CoercionAction.Fail) }
            }

        builder
            // string "5"/"1.5"/"true" must not flow into numeric or boolean fields.
            .failOn(LogicalType.Integer, CoercionInputShape.String, CoercionInputShape.Boolean)
            .failOn(LogicalType.Float, CoercionInputShape.String)
            .failOn(LogicalType.Boolean, CoercionInputShape.String, CoercionInputShape.Integer)
            // a non-string scalar must not be stringified into a textual field.
            .failOn(
                LogicalType.Textual,
                CoercionInputShape.Integer,
                CoercionInputShape.Float,
                CoercionInputShape.Boolean,
            )
    }
}
