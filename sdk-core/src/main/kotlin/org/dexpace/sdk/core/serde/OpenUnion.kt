/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * Forward-compatible union primitive: a private-constructor value type that holds exactly one of
 * several variants, dispatches through a [Visitor], and retains the raw wire node so an
 * unrecognised shape survives a round-trip instead of being dropped.
 *
 * Kotlin `sealed` classes with `permits` do not compile to Java-8 bytecode, and a closed sum type
 * crashes the moment a server adds a variant an older client cannot model. This base sidesteps both
 * problems:
 *
 *  - **One nullable slot per variant.** A concrete union subclasses [OpenUnion] with a private
 *    constructor, stores each arm in its own nullable field, and exposes a typed accessor per arm
 *    (returning `null` when that arm is not active). No `sealed`/`permits`, so it is Java-8 safe.
 *  - **Visitor dispatch.** [accept] walks the subclass's [arms] in declaration order and routes the
 *    first non-null arm to its visitor case via [Arm.dispatch]; if no arm is active (an unknown
 *    variant the deserializer could not map), it routes to [Visitor.unknown], whose default
 *    implementation throws. Generated/hand-written visitors override only the cases they care about.
 *  - **Retained raw node.** Every parsed union keeps the decoded wire value in [rawValue]
 *    (format-agnostic `Any?` — a decoded map/list/scalar, or whatever the active deserializer
 *    produced). An unknown variant carries no typed arm but still carries its [rawValue], so a
 *    serializer can re-emit it verbatim.
 *
 * Sketch of a concrete two-arm union (a generator would emit this; it is fully hand-writable):
 *
 * ```kotlin
 * public class StringOrNumber private constructor(
 *     private val string: String?,
 *     private val number: Long?,
 *     rawValue: Any?,
 * ) : OpenUnion<StringOrNumber.Visitor<*>>(rawValue) {
 *     public fun asString(): String? = string
 *     public fun asNumber(): Long? = number
 *
 *     public interface Visitor<out R> : OpenUnion.Visitor<R> {
 *         public fun visitString(value: String): R
 *         public fun visitNumber(value: Long): R
 *     }
 *
 *     override val arms: List<Arm<Visitor<*>>> = listOf(
 *         Arm(string) { v, value -> v.visitString(value as String) },
 *         Arm(number) { v, value -> v.visitNumber(value as Long) },
 *     )
 *
 *     public companion object {
 *         @JvmStatic public fun ofString(value: String): StringOrNumber = StringOrNumber(value, null, value)
 *         @JvmStatic public fun ofNumber(value: Long): StringOrNumber = StringOrNumber(null, value, value)
 *         @JvmStatic public fun ofUnknown(rawValue: Any?): StringOrNumber = StringOrNumber(null, null, rawValue)
 *     }
 * }
 * ```
 *
 * Instances are immutable. The base holds no mutable state beyond the variant slots supplied at
 * construction.
 *
 * @param VIS the concrete visitor type this union dispatches to. It must extend [Visitor].
 */
public abstract class OpenUnion<VIS : OpenUnion.Visitor<*>> protected constructor(
    rawValue: Any?,
) {
    /**
     * The decoded wire node this union was parsed from, format-agnostic. Retained on every variant
     * — including the unknown case that has no typed arm — so a serializer can re-emit the original
     * shape and forward-compatibility is preserved across the round-trip.
     */
    public val rawValue: Any? = rawValue

    /**
     * The ordered variant slots of this union, supplied by the concrete subclass. [accept] selects
     * the first [Arm] whose value is non-null. Order is the subclass's declaration order, which is
     * also the precedence used when (pathologically) more than one arm is populated.
     */
    protected abstract val arms: List<Arm<VIS>>

    /**
     * Dispatches to the visitor case for the active variant, or to [Visitor.unknown] when no arm is
     * populated (an unrecognised variant). Returns whatever the selected case returns.
     *
     * @param visitor the visitor to dispatch into. Its `unknown` case handles the forward-compat
     *   path; the default [Visitor.unknown] throws.
     */
    public fun <R> accept(visitor: Visitor<R>): R {
        for (arm in arms) {
            val value = arm.value
            if (value != null) {
                @Suppress("UNCHECKED_CAST")
                return arm.dispatch(visitor as VIS, value) as R
            }
        }
        return visitor.unknown(rawValue)
    }

    /** `true` when no variant arm is populated — i.e. this union holds an unrecognised shape. */
    public val isUnknown: Boolean
        get() = arms.all { it.value == null }

    /**
     * One variant slot: the (possibly null) value for that arm plus the dispatch closure that
     * routes a non-null value to the matching visitor case. The subclass builds one [Arm] per
     * variant; the [dispatch] closure is responsible for the cast back to the arm's concrete type.
     *
     * @param VIS the concrete visitor type, threaded through from the enclosing union.
     */
    public class Arm<VIS : Visitor<*>>(
        /** The value for this arm, or `null` when this arm is not the active variant. */
        public val value: Any?,
        private val dispatcher: (visitor: VIS, value: Any) -> Any?,
    ) {
        /** Routes a confirmed-non-null [value] into the matching case on [visitor]. */
        public fun dispatch(
            visitor: VIS,
            value: Any,
        ): Any? = dispatcher(visitor, value)
    }

    /**
     * Base visitor every concrete union visitor extends. Concrete unions add one `visitXxx` case
     * per variant; this base supplies only the forward-compatibility escape hatch.
     *
     * @param R the result type produced by every case.
     */
    public interface Visitor<out R> {
        /**
         * Invoked when the union holds an unrecognised variant. Override to handle unknown server
         * shapes gracefully (e.g. ignore, log, or inspect [rawValue]); the default throws, matching
         * the "fail loudly unless you opted in" stance.
         *
         * @param rawValue the retained raw wire node of the unrecognised variant.
         * @throws IllegalStateException by default.
         */
        public fun unknown(rawValue: Any?): R = error("Unknown union variant: $rawValue")
    }
}
