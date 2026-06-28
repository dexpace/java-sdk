/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * A dependency-free, immutable JSON value tree.
 *
 * `RawJson` is the SDK's library-agnostic representation of an arbitrary JSON value. It lets
 * `sdk-core` (and the generated DTOs that target it) carry, store, and round-trip JSON shapes the
 * model does not have a typed field for — unknown/forward-compatible properties, "wrong-type"
 * payloads that should survive a read-modify-write loop, and the `additionalProperties` bucket of a
 * schema — **without** dragging Jackson, Gson, or any other JSON library onto the core surface.
 *
 * The tree mirrors the JSON grammar exactly with six leaf/branch variants:
 *
 *  - [Obj]   — a JSON object, an **insertion-ordered** map of `String` → `RawJson`.
 *  - [Arr]   — a JSON array, an ordered list of `RawJson`.
 *  - [Str]   — a JSON string.
 *  - [Num]   — a JSON number, stored as its **verbatim literal text** so no precision is lost.
 *  - [Bool]  — a JSON `true` / `false`.
 *  - [Null]  — a JSON `null`.
 *
 * Concrete conversion between this tree and a real parser/generator lives entirely in adapter
 * modules (e.g. `sdk-serde-jackson`'s `JsonFieldModule`), exactly as [Tristate] keeps its Jackson
 * wiring out of core. `sdk-core` only exposes the value algebra so callers can construct, inspect,
 * and pattern-match a JSON value with zero third-party types.
 *
 * ### Number fidelity
 *
 * [Num] keeps the original numeric literal as text ([Num.literal]) rather than coercing to a
 * `Double` or `BigDecimal` up front. This is deliberate: a `Double` round-trip would corrupt large
 * `long` ids and high-precision decimals, the single most common lossy-JSON bug in client SDKs.
 * Typed accessors ([Num.toBigDecimal], [Num.toLongOrNull], …) parse on demand.
 *
 * Instances are deeply immutable; [Obj] and [Arr] defensively copy their inputs and expose
 * read-only views. Equality is structural throughout.
 */
public sealed class RawJson {
    /** A JSON object: an insertion-ordered mapping of member name to value. */
    public class Obj private constructor(
        private val members: Map<String, RawJson>,
    ) : RawJson() {
        /** The object members as an insertion-ordered, read-only map. */
        public val entries: Map<String, RawJson>
            get() = members

        /** The member names, in insertion order. */
        public val keys: Set<String>
            get() = members.keys

        /** Returns the value for [key], or `null` if the object has no such member. */
        public operator fun get(key: String): RawJson? = members[key]

        /** Returns `true` if the object has a member named [key]. */
        public operator fun contains(key: String): Boolean = members.containsKey(key)

        /** Returns `true` if the object has no members. */
        public fun isEmpty(): Boolean = members.isEmpty()

        /** The number of members. */
        public val size: Int
            get() = members.size

        override fun equals(other: Any?): Boolean = other is Obj && other.members == members

        override fun hashCode(): Int = members.hashCode()

        override fun toString(): String =
            members.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "${quote(k)}:$v" }

        public companion object {
            /** An empty JSON object singleton. */
            @JvmField
            public val EMPTY: Obj = Obj(emptyMap())

            /**
             * Builds an [Obj] from [members], preserving the iteration order of the supplied map.
             * The input is defensively copied into an insertion-ordered map, so later mutation of
             * the caller's collection cannot leak into the immutable tree.
             */
            @JvmStatic
            public fun of(members: Map<String, RawJson>): Obj =
                if (members.isEmpty()) EMPTY else Obj(LinkedHashMap(members))
        }
    }

    /** A JSON array: an ordered list of values. */
    public class Arr private constructor(
        private val elements: List<RawJson>,
    ) : RawJson() {
        /** The array elements as a read-only list. */
        public val values: List<RawJson>
            get() = elements

        /** Returns the element at [index]. Throws [IndexOutOfBoundsException] if out of range. */
        public operator fun get(index: Int): RawJson = elements[index]

        /** Returns `true` if the array has no elements. */
        public fun isEmpty(): Boolean = elements.isEmpty()

        /** The number of elements. */
        public val size: Int
            get() = elements.size

        override fun equals(other: Any?): Boolean = other is Arr && other.elements == elements

        override fun hashCode(): Int = elements.hashCode()

        override fun toString(): String = elements.joinToString(prefix = "[", postfix = "]")

        public companion object {
            /** An empty JSON array singleton. */
            @JvmField
            public val EMPTY: Arr = Arr(emptyList())

            /** Builds an [Arr] from [elements], defensively copied so the tree stays immutable. */
            @JvmStatic
            public fun of(elements: List<RawJson>): Arr = if (elements.isEmpty()) EMPTY else Arr(ArrayList(elements))
        }
    }

    /** A JSON string value. */
    public data class Str(public val value: String) : RawJson() {
        override fun toString(): String = quote(value)
    }

    /**
     * A JSON number, stored as its verbatim source [literal] to preserve full precision.
     *
     * Typed accessors parse the literal on demand; they return `null` (rather than throwing) when
     * the literal does not fit the requested type, so callers can probe a representation without a
     * try/catch.
     */
    public data class Num(public val literal: String) : RawJson() {
        /** Parses the literal as a [java.math.BigDecimal] — the lossless numeric view. */
        public fun toBigDecimal(): java.math.BigDecimal = java.math.BigDecimal(literal)

        /** Parses the literal as a [Double]. Always succeeds (may be `Infinity` for huge values). */
        public fun toDouble(): Double = literal.toDouble()

        /** Parses the literal as a [Long], or `null` if it is not an integer in `Long` range. */
        public fun toLongOrNull(): Long? = literal.toLongOrNull()

        /** Parses the literal as an [Int], or `null` if it is not an integer in `Int` range. */
        public fun toIntOrNull(): Int? = literal.toIntOrNull()

        override fun toString(): String = literal

        public companion object {
            /** Builds a [Num] from a [Long], using its canonical decimal rendering. */
            @JvmStatic
            public fun of(value: Long): Num = Num(value.toString())

            /** Builds a [Num] from an [Int], using its canonical decimal rendering. */
            @JvmStatic
            public fun of(value: Int): Num = Num(value.toString())

            /** Builds a [Num] from a [java.math.BigDecimal], using its canonical text. */
            @JvmStatic
            public fun of(value: java.math.BigDecimal): Num = Num(value.toPlainString())
        }
    }

    /** A JSON boolean value. */
    public data class Bool(public val value: Boolean) : RawJson() {
        override fun toString(): String = value.toString()

        public companion object {
            /** The JSON `true` singleton. */
            @JvmField
            public val TRUE: Bool = Bool(true)

            /** The JSON `false` singleton. */
            @JvmField
            public val FALSE: Bool = Bool(false)

            /** Returns [TRUE] or [FALSE] for [value]. */
            @JvmStatic
            public fun of(value: Boolean): Bool = if (value) TRUE else FALSE
        }
    }

    /** The JSON `null` literal. */
    public object Null : RawJson() {
        override fun toString(): String = "null"
    }

    /** Returns `true` if this node is the JSON [Null] literal. */
    public val isNull: Boolean
        get() = this === Null

    public companion object {
        /** Radix for the `\uXXXX` escape's hexadecimal digits. */
        private const val HEX_RADIX = 16

        /** A `\uXXXX` escape is always four hex digits, zero-padded. */
        private const val UNICODE_ESCAPE_DIGITS = 4

        /**
         * Minimal RFC 8259 string quoting for [toString] previews. This is a *rendering* helper for
         * diagnostics, not a serializer — real emission is the adapter's job. It escapes the
         * mandatory control set so a preview is always valid-looking JSON.
         */
        private fun quote(raw: String): String {
            val sb = StringBuilder(raw.length + 2)
            sb.append('"')
            for (ch in raw) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (ch < ' ') {
                            sb
                                .append("\\u")
                                .append(ch.code.toString(radix = HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                        } else {
                            sb.append(ch)
                        }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
