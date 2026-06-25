/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.impl.PropertySerializerMap
import com.fasterxml.jackson.databind.type.TypeFactory
import org.dexpace.sdk.core.serde.Tristate

/**
 * Jackson module wiring [Tristate]<T> ser/de.
 *
 * ## Why it's tricky
 *
 * `Tristate<T>` has three states that must be preserved through a JSON round trip:
 *  - [Tristate.Absent]  ⟷ the field key is missing from the JSON object entirely.
 *  - [Tristate.Null]    ⟷ the field key is present with a JSON `null` value.
 *  - [Tristate.Present] ⟷ the field key is present with a payload value.
 *
 * Jackson's default property handling treats nulls and missing keys identically (both fire the
 * deserializer's null-value hook), and a default serializer always writes a key for every
 * property — which would conflate Absent and Null. Both ends need explicit hooks.
 *
 * ## Deserialization
 *
 * [TristateDeserializer] implements [ContextualDeserializer] so the framework can hand us the
 * concrete `T` per call-site. The `deserialize` hook fires only on a present, non-null token,
 * producing [Tristate.Present]. The `getNullValue` hook fires when the key is present and
 * explicitly null, producing [Tristate.Null]. For absence we rely on the field's compile-time
 * default — generated DTOs should default `Tristate<T>` fields to `Tristate.Absent`.
 *
 * ## Serialization
 *
 * [TristateSerializer] writes Null as JSON `null` and Present(v) as `v` (recurring through the
 * mapper). Absent is the absence case — handled by [TristatePropertyWriter] (installed via the
 * [BeanSerializerModifier]) which **skips the property** entirely when the value is Absent.
 *
 * The serializer also overrides `isEmpty` so callers relying on `@JsonInclude(NON_EMPTY)` get
 * Absent omission for free even without the bean-property wrapper.
 */
public class TristateModule : SimpleModule(MODULE_NAME, com.fasterxml.jackson.core.Version(1, 0, 0, null, null, null)) {
    public companion object {
        private const val MODULE_NAME = "TristateModule"
    }

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializers(TristateDeserializers())
        context.addSerializers(
            com.fasterxml.jackson.databind.module.SimpleSerializers().apply {
                addSerializer(Tristate::class.java, TristateSerializer())
            },
        )
        context.addBeanSerializerModifier(TristateSerializerModifier())
    }
}

/** The element [JavaType] for `Object` — the fallback when a `Tristate` is raw or `Tristate<*>`. */
private val ANY_TYPE: JavaType = TypeFactory.defaultInstance().constructType(Any::class.java)

/** The first contained type argument, or `null` when the type is raw / carries no parameters. */
private fun JavaType.firstContainedOrNull(): JavaType? = if (containedTypeCount() > 0) containedType(0) else null

/** Whether this type is (or extends) [Tristate]. */
private fun JavaType.isTristate(): Boolean = Tristate::class.java.isAssignableFrom(rawClass)

/**
 * Resolver that returns [TristateDeserializer] for any [Tristate] target type. Needed because
 * the default [SimpleModule.addDeserializer] path keys on the raw class but `Tristate<*>` calls
 * arrive parametrically — a `Deserializers` callback gives us the concrete [JavaType].
 */
internal class TristateDeserializers internal constructor() : Deserializers.Base() {
    override fun findBeanDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription,
    ): JsonDeserializer<*>? =
        if (type.isTristate()) {
            // Inner type defaults to Object when the user wrote `Tristate<*>` or raw `Tristate`.
            TristateDeserializer(type.firstContainedOrNull() ?: ANY_TYPE)
        } else {
            null
        }
}

internal class TristateDeserializer internal constructor(
    private val innerType: JavaType?,
) : JsonDeserializer<Tristate<*>>(), ContextualDeserializer {
    override fun createContextual(
        ctxt: DeserializationContext,
        property: BeanProperty?,
    ): JsonDeserializer<*> {
        // When wired through a typed BeanProperty (the common path: a field on a DTO), the
        // contextual type is the property's declared type and `containedType(0)` is the T in
        // `Tristate<T>`. Falling back to the constructor-provided innerType lets callers that
        // deserialize Tristate directly (without a wrapping bean) still get correct typing.
        val resolved: JavaType =
            property?.type?.takeIf { it.isTristate() }?.firstContainedOrNull()
                ?: innerType ?: ANY_TYPE
        return TristateDeserializer(resolved)
    }

    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): Tristate<*> {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return Tristate.Null
        }
        val target = innerType ?: ANY_TYPE
        val value: Any? = ctxt.readValue(p, target)
        return if (value == null) Tristate.Null else Tristate.Present(value)
    }

    /**
     * Jackson invokes this when a JSON property's value is explicitly null. Distinguishes
     * "present and null" from "absent entirely" — the latter never reaches the deserializer
     * because Jackson short-circuits missing keys.
     */
    override fun getNullValue(ctxt: DeserializationContext): Tristate<*> = Tristate.Null

    /**
     * The empty value (returned when a missing key falls through to "absent" via property
     * defaults). Most DTOs default the field to `Tristate.Absent` at the Kotlin level, but if
     * Jackson asks us for the empty value (no default available), we still honour absence.
     */
    override fun getEmptyValue(ctxt: DeserializationContext): Tristate<*> = Tristate.Absent
}

/**
 * Writes [Tristate.Present] payloads through the parent mapper (so nested types serialize
 * correctly) and [Tristate.Null] as JSON `null`. [Tristate.Absent] never reaches this serializer
 * in the typical bean-property path: [TristatePropertyWriter] short-circuits the property
 * write entirely. For top-level / raw `Tristate` serialization (no wrapping bean), this
 * implementation writes `null` for both Absent and Null — JSON itself has no "field is
 * missing" concept when there's no enclosing object to omit a key from.
 */
internal class TristateSerializer internal constructor() : JsonSerializer<Tristate<*>>() {
    // Cached per-type sub-serializer lookup so repeated calls with the same T don't pay
    // ObjectMapper lookup cost. PropertySerializerMap.findAndAddPrimarySerializer is the
    // canonical Jackson idiom for this.
    @Volatile
    private var dynamicSerializers: PropertySerializerMap = PropertySerializerMap.emptyForProperties()

    override fun serialize(
        value: Tristate<*>,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        when (value) {
            Tristate.Absent, Tristate.Null -> gen.writeNull()
            is Tristate.Present<*> -> writePresent(value.value, gen, serializers)
        }
    }

    /**
     * Serializer for the absent case — Jackson asks us via `isEmpty(...)` whether the value
     * should be omitted under `@JsonInclude(NON_EMPTY)` / `Include.NON_ABSENT`. Returning true
     * for Absent gives us the omission semantics consumers expect.
     */
    override fun isEmpty(
        provider: SerializerProvider?,
        value: Tristate<*>?,
    ): Boolean = value == null || value is Tristate.Absent

    private fun writePresent(
        inner: Any?,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        if (inner == null) {
            // Defensive only — Tristate.Present is now bounded `T : Any`, so a Present(null) cannot
            // be constructed through the public API. This branch survives as a belt-and-suspenders
            // guard against a value smuggled in via raw/erased generics; a Null is the right shape.
            gen.writeNull()
            return
        }
        val klass = inner::class.java
        val map = dynamicSerializers
        val cached: JsonSerializer<Any>? = map.serializerFor(klass)
        val resolved: JsonSerializer<Any> =
            cached ?: run {
                val result = map.findAndAddPrimarySerializer(klass, serializers, null)
                dynamicSerializers = result.map
                result.serializer
            }
        resolved.serialize(inner, gen, serializers)
    }
}

/**
 * [BeanSerializerModifier] that rewrites every [Tristate]-typed bean property to use
 * [TristatePropertyWriter], which knows to skip the field entirely when the value is
 * [Tristate.Absent]. Without this hook, Jackson would always emit the field key — collapsing
 * Absent into Null on the wire.
 */
internal class TristateSerializerModifier internal constructor() : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> {
        beanProperties.replaceAll { writer ->
            if (writer.type.isTristate()) TristatePropertyWriter(writer) else writer
        }
        return beanProperties
    }
}

/**
 * A [BeanPropertyWriter] that delegates to the standard writer for [Tristate.Null] and
 * [Tristate.Present], but **omits the property entirely** when the value is [Tristate.Absent].
 *
 * This is the Jackson-idiomatic way to model `@JsonInclude(NON_ABSENT)` semantics for a custom
 * sentinel — overriding `serializeAsField` lets us choose between calling the standard write
 * path and doing nothing (which causes the property key to never appear in the output).
 */
internal class TristatePropertyWriter internal constructor(
    base: BeanPropertyWriter,
) : BeanPropertyWriter(base) {
    override fun serializeAsField(
        bean: Any,
        gen: JsonGenerator,
        prov: SerializerProvider,
    ) {
        val raw = get(bean)
        if (raw is Tristate.Absent) {
            // Skip the property — gen never sees a field name written.
            return
        }
        super.serializeAsField(bean, gen, prov)
    }

    override fun serializeAsElement(
        bean: Any,
        gen: JsonGenerator,
        prov: SerializerProvider,
    ) {
        val raw = get(bean)
        if (raw is Tristate.Absent) {
            // In array context Jackson cannot truly skip — emit a JSON null. This is a
            // best-effort: Absent in an array is semantically odd, but consumers occasionally
            // do it and we should not throw.
            prov.defaultSerializeNull(gen)
            return
        }
        super.serializeAsElement(bean, gen, prov)
    }
}
