/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * An immutable, insertion-ordered snapshot of the unknown ("additional") JSON properties a model
 * carried on the wire — the runtime primitive behind lossless `additionalProperties` pass-through.
 *
 * The SDK's default mapper does **not** fail on unknown fields (forward compatibility), but Jackson's
 * default binding then *drops* them: a read-modify-write loop silently discards any property the
 * client model has no field for, including server-added fields the client never meant to touch. A
 * generated DTO that mixes [AdditionalProperties] in captures those leftover keys into this holder at
 * deserialization and re-emits them on serialization, so the round-trip is lossless.
 *
 * Values are [RawJson] trees, so the holder stays dependency-free and preserves the exact JSON shape
 * — including types the model would not otherwise understand. The map is insertion-ordered so a
 * round-trip is byte-stable with respect to key order, and immutable so a built model cannot be
 * mutated after the fact; use [newBuilder] to derive a modified copy.
 *
 * This is a hand-written runtime base usable today; it is also the exact shape a generator would
 * target for `@JsonAnySetter`/`@JsonAnyGetter`-style pass-through. The adapter
 * (`sdk-serde-jackson`'s `JsonFieldModule`) supplies the Jackson capture/emit wiring; this type
 * holds no library dependency.
 */
public class AdditionalProperties private constructor(
    private val properties: Map<String, RawJson>,
) {
    /** The captured unknown properties as an insertion-ordered, read-only map. */
    public val entries: Map<String, RawJson>
        get() = properties

    /** The captured property names, in insertion order. */
    public val keys: Set<String>
        get() = properties.keys

    /** Returns the captured value for [key], or `null` if no such property was present. */
    public operator fun get(key: String): RawJson? = properties[key]

    /** Returns `true` if a property named [key] was captured. */
    public operator fun contains(key: String): Boolean = properties.containsKey(key)

    /** Returns `true` if no additional properties were captured. */
    public fun isEmpty(): Boolean = properties.isEmpty()

    /** The number of captured properties. */
    public val size: Int
        get() = properties.size

    /** Renders the captured properties as a [RawJson.Obj] for emission. */
    public fun toRawJson(): RawJson.Obj = RawJson.Obj.of(properties)

    /** Returns a [Builder] pre-filled with this snapshot's properties, for deriving a modified copy. */
    public fun newBuilder(): Builder = Builder(properties)

    override fun equals(other: Any?): Boolean = other is AdditionalProperties && other.properties == properties

    override fun hashCode(): Int = properties.hashCode()

    override fun toString(): String = "AdditionalProperties(${toRawJson()})"

    /**
     * Builder for [AdditionalProperties]. Insertion order of [put] calls is preserved. Implements
     * [Builder]<[AdditionalProperties]> per the SDK's immutable-type convention.
     */
    public class Builder internal constructor(
        initial: Map<String, RawJson>,
    ) : org.dexpace.sdk.core.generics.Builder<AdditionalProperties> {
        private val properties: LinkedHashMap<String, RawJson> = LinkedHashMap(initial)

        /** Creates an empty builder. */
        public constructor() : this(emptyMap())

        /** Records the additional property [key] → [value], replacing any prior value for [key]. */
        public fun put(
            key: String,
            value: RawJson,
        ): Builder =
            apply {
                properties[key] = value
            }

        /** Records every entry of [values], in iteration order. */
        public fun putAll(values: Map<String, RawJson>): Builder =
            apply {
                properties.putAll(values)
            }

        /** Removes the captured property [key], if present. */
        public fun remove(key: String): Builder =
            apply {
                properties.remove(key)
            }

        /** Removes all captured properties. */
        public fun clear(): Builder =
            apply {
                properties.clear()
            }

        override fun build(): AdditionalProperties =
            if (properties.isEmpty()) EMPTY else AdditionalProperties(LinkedHashMap(properties))
    }

    public companion object {
        /** An empty snapshot singleton. */
        @JvmField
        public val EMPTY: AdditionalProperties = AdditionalProperties(emptyMap())

        /** Returns the [EMPTY] snapshot. Convenience factory for default field values. */
        @JvmStatic
        public fun empty(): AdditionalProperties = EMPTY

        /** Builds a snapshot from [properties], defensively copied and insertion-ordered. */
        @JvmStatic
        public fun of(properties: Map<String, RawJson>): AdditionalProperties =
            if (properties.isEmpty()) EMPTY else AdditionalProperties(LinkedHashMap(properties))

        /** Creates an empty [Builder]. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
