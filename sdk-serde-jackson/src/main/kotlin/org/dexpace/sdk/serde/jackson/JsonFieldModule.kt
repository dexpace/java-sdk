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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.module.SimpleSerializers
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.ContextualSerializer
import com.fasterxml.jackson.databind.type.TypeFactory
import org.dexpace.sdk.core.serde.JsonField
import org.dexpace.sdk.core.serde.RawJson

/**
 * Jackson module wiring [JsonField]<T> and [RawJson] ser/de, plus the conversion between the
 * dependency-free [RawJson] tree (defined in `sdk-core`) and Jackson's own node model.
 *
 * This mirrors `TristateModule` exactly: `sdk-core` owns the pure value types ([JsonField],
 * [RawJson]); all Jackson coupling lives here in the adapter.
 *
 * ## JsonField's four states on the wire
 *
 *  - [JsonField.Missing] ⟷ the key is missing from the JSON object entirely.
 *  - [JsonField.Null]    ⟷ the key is present with a JSON `null`.
 *  - [JsonField.Known]   ⟷ the key is present and the value bound cleanly to `T`.
 *  - [JsonField.Raw]     ⟷ the key is present but the value did **not** bind to `T`; the original
 *    JSON is captured as a [RawJson] tree and re-emitted verbatim.
 *
 * The [JsonFieldDeserializer] first reads the value into an untyped [JsonNode], then attempts to
 * convert that node into `T`. On success it produces [JsonField.Known]; on a binding failure
 * (`JsonMappingException` / `IllegalArgumentException`) it falls back to [JsonField.Raw], so a
 * shape the model did not anticipate is preserved rather than fatal. Missing keys default to
 * [JsonField.Missing] (generated DTOs should default the field to it), and explicit null routes
 * through `getNullValue` to [JsonField.Null].
 *
 * Serialization mirrors `TristateModule`: [JsonFieldPropertyWriter] omits the property entirely
 * for [JsonField.Missing]; the serializer writes Null as JSON `null`, Known through the parent
 * mapper, and Raw by emitting the captured [RawJson] tree.
 */
public class JsonFieldModule :
    SimpleModule(MODULE_NAME, com.fasterxml.jackson.core.Version(1, 0, 0, null, null, null)) {
    public companion object {
        private const val MODULE_NAME = "JsonFieldModule"
    }

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializers(JsonFieldDeserializers())
        context.addSerializers(
            SimpleSerializers().apply {
                addSerializer(JsonField::class.java, JsonFieldSerializer())
                addSerializer(RawJson::class.java, RawJsonSerializer())
            },
        )
        context.addBeanSerializerModifier(JsonFieldSerializerModifier())
    }
}

/**
 * Resolver that returns a [JsonFieldDeserializer] for any [JsonField] target type, threading the
 * concrete `T` through from the parametric [JavaType] (the same trick `TristateModule` uses).
 */
internal class JsonFieldDeserializers internal constructor() : Deserializers.Base() {
    override fun findBeanDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription,
    ): JsonDeserializer<*>? =
        if (JsonField::class.java.isAssignableFrom(type.rawClass)) {
            val inner: JavaType =
                if (type.containedTypeCount() > 0) {
                    type.containedType(0)
                } else {
                    TypeFactory.defaultInstance().constructType(Any::class.java)
                }
            JsonFieldDeserializer(inner)
        } else {
            null
        }
}

internal class JsonFieldDeserializer internal constructor(
    private val innerType: JavaType?,
) : JsonDeserializer<JsonField<*>>(), ContextualDeserializer {
    override fun createContextual(
        ctxt: DeserializationContext,
        property: BeanProperty?,
    ): JsonDeserializer<*> {
        val resolved: JavaType =
            property?.type?.takeIf { JsonField::class.java.isAssignableFrom(it.rawClass) }?.let { wrapper ->
                if (wrapper.containedTypeCount() > 0) wrapper.containedType(0) else null
            } ?: innerType ?: TypeFactory.defaultInstance().constructType(Any::class.java)
        return JsonFieldDeserializer(resolved)
    }

    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): JsonField<*> {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return JsonField.Null
        }
        // Read the value into a structure-preserving node first, then try to bind it to T. A bind
        // failure must not be fatal: we park the original JSON as Raw so it round-trips.
        val node: JsonNode = ctxt.readTree(p)
        if (node.isNull) {
            return JsonField.Null
        }
        val target = innerType
        if (target == null || target.rawClass == Any::class.java) {
            // No concrete target type: treat the node as Raw so nothing is lost or coerced.
            return JsonField.Raw(RawJsonConversions.fromNode(node))
        }
        return bindOrRaw(node, target, ctxt)
    }

    private fun bindOrRaw(
        node: JsonNode,
        target: JavaType,
        ctxt: DeserializationContext,
    ): JsonField<*> =
        try {
            val value: Any? = ctxt.readTreeAsValue(node, target)
            if (value == null) JsonField.Null else JsonField.Known(value)
        } catch (_: com.fasterxml.jackson.databind.JsonMappingException) {
            JsonField.Raw(RawJsonConversions.fromNode(node))
        } catch (_: IllegalArgumentException) {
            JsonField.Raw(RawJsonConversions.fromNode(node))
        }

    /** Fires when the key is present with an explicit null. */
    override fun getNullValue(ctxt: DeserializationContext): JsonField<*> = JsonField.Null

    /** Fires when a missing key falls through to "absent" via property defaults. */
    override fun getEmptyValue(ctxt: DeserializationContext): JsonField<*> = JsonField.Missing
}

/**
 * Writes [JsonField.Null] as JSON `null`, [JsonField.Known] through the parent mapper, and
 * [JsonField.Raw] by emitting its captured [RawJson]. [JsonField.Missing] is short-circuited by
 * [JsonFieldPropertyWriter] in the bean-property path; at the top level (no wrapping bean) it is
 * written as `null`, since JSON has no "missing" concept without an enclosing object.
 */
internal class JsonFieldSerializer internal constructor() :
    JsonSerializer<JsonField<*>>(), ContextualSerializer {
        override fun createContextual(
            prov: SerializerProvider,
            property: BeanProperty?,
        ): JsonSerializer<*> = this

        override fun serialize(
            value: JsonField<*>,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            when (value) {
                JsonField.Missing, JsonField.Null -> gen.writeNull()
                is JsonField.Known<*> -> serializers.defaultSerializeValue(value.value, gen)
                is JsonField.Raw -> RawJsonConversions.write(value.json, gen)
            }
        }

        /** Honour `@JsonInclude(NON_EMPTY)` — a Missing field should be omitted. */
        override fun isEmpty(
            provider: SerializerProvider?,
            value: JsonField<*>?,
        ): Boolean = value == null || value is JsonField.Missing
    }

/**
 * Serializer for a bare [RawJson] tree (e.g. an `additionalProperties` bucket exposed as
 * `RawJson`), so it emits as real JSON rather than via reflection.
 */
internal class RawJsonSerializer internal constructor() : JsonSerializer<RawJson>() {
    override fun serialize(
        value: RawJson,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        RawJsonConversions.write(value, gen)
    }
}

/**
 * Rewrites every [JsonField]-typed bean property to use [JsonFieldPropertyWriter], which skips the
 * field for [JsonField.Missing] (so Missing never collapses into Null on the wire).
 */
internal class JsonFieldSerializerModifier internal constructor() : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> {
        for (i in beanProperties.indices) {
            val writer = beanProperties[i]
            if (JsonField::class.java.isAssignableFrom(writer.type.rawClass)) {
                beanProperties[i] = JsonFieldPropertyWriter(writer)
            }
        }
        return beanProperties
    }
}

/**
 * A [BeanPropertyWriter] that omits the property entirely when its value is [JsonField.Missing],
 * delegating to the standard writer for every other variant.
 */
internal class JsonFieldPropertyWriter internal constructor(
    base: BeanPropertyWriter,
) : BeanPropertyWriter(base) {
    override fun serializeAsField(
        bean: Any,
        gen: JsonGenerator,
        prov: SerializerProvider,
    ) {
        if (get(bean) is JsonField.Missing) {
            return
        }
        super.serializeAsField(bean, gen, prov)
    }

    override fun serializeAsElement(
        bean: Any,
        gen: JsonGenerator,
        prov: SerializerProvider,
    ) {
        if (get(bean) is JsonField.Missing) {
            // Arrays cannot truly skip an element; emit null as the least-surprising fallback.
            prov.defaultSerializeNull(gen)
            return
        }
        super.serializeAsElement(bean, gen, prov)
    }
}

/**
 * Bidirectional conversion between the dependency-free [RawJson] tree and Jackson's wire model.
 *
 * Reading goes through [JsonNode] (structure-preserving, no coercion); writing streams straight to a
 * [JsonGenerator]. Numbers are carried as their verbatim text via [JsonGenerator.writeNumber] so a
 * large `long` id or high-precision decimal round-trips losslessly.
 */
internal object RawJsonConversions {
    fun fromNode(node: JsonNode): RawJson =
        when {
            node.isNull || node.isMissingNode -> RawJson.Null
            node.isObject -> objFromNode(node as ObjectNode)
            node.isArray -> arrFromNode(node as ArrayNode)
            node.isTextual -> RawJson.Str(node.textValue())
            node.isBoolean -> RawJson.Bool.of(node.booleanValue())
            node.isNumber -> RawJson.Num(node.asText())
            else -> RawJson.Str(node.asText())
        }

    private fun objFromNode(node: ObjectNode): RawJson.Obj {
        val members = LinkedHashMap<String, RawJson>(node.size())
        val fields = node.fields()
        while (fields.hasNext()) {
            val (key, value) = fields.next()
            members[key] = fromNode(value)
        }
        return RawJson.Obj.of(members)
    }

    private fun arrFromNode(node: ArrayNode): RawJson.Arr {
        val elements = ArrayList<RawJson>(node.size())
        for (element in node) {
            elements.add(fromNode(element))
        }
        return RawJson.Arr.of(elements)
    }

    fun write(
        value: RawJson,
        gen: JsonGenerator,
    ) {
        when (value) {
            is RawJson.Null -> gen.writeNull()
            is RawJson.Str -> gen.writeString(value.value)
            is RawJson.Bool -> gen.writeBoolean(value.value)
            is RawJson.Num -> gen.writeNumber(value.literal)
            is RawJson.Arr -> writeArr(value, gen)
            is RawJson.Obj -> writeObj(value, gen)
        }
    }

    private fun writeObj(
        value: RawJson.Obj,
        gen: JsonGenerator,
    ) {
        gen.writeStartObject()
        for ((key, member) in value.entries) {
            gen.writeFieldName(key)
            write(member, gen)
        }
        gen.writeEndObject()
    }

    private fun writeArr(
        value: RawJson.Arr,
        gen: JsonGenerator,
    ) {
        gen.writeStartArray()
        for (element in value.values) {
            write(element, gen)
        }
        gen.writeEndArray()
    }
}
