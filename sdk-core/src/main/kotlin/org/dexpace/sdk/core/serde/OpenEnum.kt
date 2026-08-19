/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * Forward-compatible enum primitive: an **open** value type over a single wire string that never
 * throws during deserialization, even when the server sends a value this SDK build has never heard
 * of.
 *
 * Closed Kotlin/Java enums are a poor fit for evolving HTTP APIs: a server that adds a new variant
 * breaks every older client the moment that variant reaches the wire, because `valueOf` throws.
 * This type follows the two-enum pattern used by hand-written and generated DTOs alike:
 *
 *  - A closed [Known] enum, generated/hand-written by the caller, enumerates exactly the variants
 *    this build understands.
 *  - A parallel [Value] enum mirrors [Known] and adds a single `UNKNOWN` sentinel for any value
 *    outside the closed set. The sentinel is a lint-clean name, not an underscore-prefixed marker,
 *    so it reads naturally in a `when`.
 *  - The original wire string is always retained in [rawValue], so an unknown value round-trips
 *    back to the server unchanged rather than being silently dropped.
 *
 * Concrete enum types subclass [OpenEnum] with a private constructor and expose `@JvmStatic`
 * factories plus public constants, e.g.:
 *
 * ```kotlin
 * public class ReasoningEffort private constructor(
 *     rawValue: String,
 * ) : OpenEnum<ReasoningEffort.Known, ReasoningEffort.Value>(rawValue) {
 *     public enum class Known(public val wireValue: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }
 *     public enum class Value { LOW, MEDIUM, HIGH, UNKNOWN }
 *
 *     public companion object {
 *         @JvmField public val LOW: ReasoningEffort = ReasoningEffort("low")
 *         @JvmStatic public fun of(value: String): ReasoningEffort = ReasoningEffort(value)
 *     }
 *
 *     override fun knownToValue(known: Known): Value = Value.valueOf(known.name)
 *     override fun resolveKnown(rawValue: String): Known? = Known.entries.firstOrNull { it.wireValue == rawValue }
 *     override fun unknownValue(): Value = Value.UNKNOWN
 * }
 * ```
 *
 * The deserializer for such a type calls the `of`-style factory with the raw string and never
 * inspects whether it is known; the *consumer* decides whether to branch on [value] (total,
 * including the unknown sentinel) or to demand a [known] (throws on unknown). Encoding always emits
 * [rawValue].
 *
 * Instances are immutable. Equality is by [rawValue] only — two instances with the same raw string
 * are equal regardless of subclass identity, mirroring how the wire treats them.
 *
 * @param K the closed enum of variants this build understands.
 * @param V the parallel enum mirroring [K] plus an unknown sentinel.
 */
public abstract class OpenEnum<K : Enum<K>, V : Enum<V>> protected constructor(
    rawValue: String,
) {
    /**
     * The exact string received on the wire (or supplied by the caller). Always preserved so an
     * unrecognised value can be re-serialized unchanged instead of being lost.
     */
    public val rawValue: String = rawValue

    /**
     * Maps a [Known][K] variant to its mirror in [V]. Implementations typically delegate to
     * `Value.valueOf(known.name)` when the two enums share variant names.
     */
    protected abstract fun knownToValue(known: K): V

    /**
     * Resolves [rawValue] back to a [Known][K] variant, or `null` if the raw string is outside the
     * closed set. Implementations match on whatever wire spelling the variant uses.
     */
    protected abstract fun resolveKnown(rawValue: String): K?

    /** Returns the [V] sentinel that represents "value outside the closed set". */
    protected abstract fun unknownValue(): V

    /**
     * Total classifier over [V]: returns the mirrored known variant when [rawValue] is recognised,
     * otherwise the [unknownValue] sentinel. Never throws — use this when handling an enum
     * exhaustively (e.g. a `when` with an `UNKNOWN` arm) so a new server variant degrades
     * gracefully instead of crashing.
     */
    public fun value(): V {
        val known = resolveKnown(rawValue)
        return if (known == null) unknownValue() else knownToValue(known)
    }

    /**
     * Returns the known variant for [rawValue], or `null` if the value is outside the closed set.
     * The nullable counterpart to [known] for callers that prefer to branch rather than catch.
     */
    public fun knownOrNull(): K? = resolveKnown(rawValue)

    /**
     * Returns the known variant for [rawValue], throwing if the value is unknown to this build.
     *
     * Use this only at call-sites that genuinely cannot proceed with an unrecognised value;
     * prefer [value] or [knownOrNull] on any path that must survive server-side evolution.
     *
     * @throws IllegalStateException if [rawValue] is not a recognised variant.
     */
    public fun known(): K = resolveKnown(rawValue) ?: error("Unknown value for ${javaClass.simpleName}: $rawValue")

    /** `true` when [rawValue] does not correspond to any known variant of this build. */
    public val isUnknown: Boolean
        get() = resolveKnown(rawValue) == null

    /**
     * Equal when the other value is an [OpenEnum] carrying the same [rawValue]. Subclass identity
     * is intentionally ignored: equality follows the wire, where only the string matters.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenEnum<*, *>) return false
        return rawValue == other.rawValue
    }

    override fun hashCode(): Int = rawValue.hashCode()

    /** Renders [rawValue] verbatim so logs and `toString` round-trip the wire form. */
    override fun toString(): String = rawValue
}
