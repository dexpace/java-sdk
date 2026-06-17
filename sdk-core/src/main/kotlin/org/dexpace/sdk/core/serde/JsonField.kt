/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * Four-valued container for a model field that must distinguish, at the serialization boundary,
 * between four mutually exclusive states:
 *
 *  - [Missing] — the property was absent from the wire payload entirely.
 *  - [Null]    — the property was present and explicitly `null`.
 *  - [Known]   — the property was present with a value that bound cleanly to the declared type `T`.
 *  - [Raw]     — the property was present but its JSON shape **did not** match `T` (a forward-compat
 *    or wrong-type payload), captured losslessly as a [RawJson] tree so it survives a round-trip.
 *
 * [Tristate] already models the first three of these (absent / null / present). What it lacks — and
 * what a code generator targeting forward-compatible models needs — is the fourth, "present but
 * unbindable", escape hatch. `JsonField` adds [Raw] so a deserializer never has to throw on a
 * surprising payload and never has to drop it: the original JSON is preserved and re-emitted
 * verbatim on write.
 *
 * Like [Tristate], all concrete Jackson (or other library) conversion lives in adapter modules
 * (`sdk-serde-jackson`'s `JsonFieldModule`); `sdk-core` only exposes the abstract sum type and the
 * dependency-free [RawJson] tree it carries, so generated DTOs can declare `JsonField<T>` fields
 * without pulling a JSON library onto the core surface.
 *
 * ### Tristate interop — read-path vs. PATCH-path
 *
 * `JsonField` and [Tristate] overlap but are **not interchangeable**, and merging them carelessly
 * loses information:
 *
 *  - **Read path (forward-compatible deserialization).** Use `JsonField<T>`. A server can add fields
 *    or change a field's shape ahead of the client; [Raw] absorbs the unexpected without failing.
 *    [toTristate] collapses `JsonField` down to `Tristate` for callers that only care about the
 *    three classic states — but it is **lossy**: a [Raw] node cannot be represented as a `Tristate`,
 *    so [toTristate] folds it into [Tristate.Present] wrapping the [RawJson], preserving the value
 *    while flattening the "wrong type" signal. Pick the right tool for the layer.
 *  - **PATCH path (three-state write).** Use [Tristate]. A `PATCH` body has exactly three meaningful
 *    states — leave-alone / clear / set — and no use for a "wrong type" fourth state, which would be
 *    a client bug rather than a wire reality. [fromTristate] lifts a `Tristate<T>` into the matching
 *    `JsonField<T>` ([Tristate.Absent] → [Missing], [Tristate.Null] → [Null], [Tristate.Present] →
 *    [Known]); it never produces [Raw].
 *
 * The boundary: deserialize into `JsonField`, but never feed a `JsonField` that might be [Raw] into
 * a PATCH builder expecting a clean three-state `Tristate` — convert explicitly and decide what a
 * [Raw] means for your write.
 *
 * Covariant in `T`, so `Known<String>` is assignable to `JsonField<CharSequence>`; the [Missing],
 * [Null], and [Raw] variants carry no `T` and satisfy any `JsonField<T>` target without a cast.
 *
 * Instances are immutable; equality is structural for [Known] / [Raw] and referential for the
 * [Missing] / [Null] singletons.
 */
public sealed class JsonField<out T> {
    /**
     * Sentinel meaning "the field was not present in the wire payload".
     *
     * Distinct from [Null]: [Missing] means the key never appeared; [Null] means it appeared with a
     * `null` value.
     */
    public object Missing : JsonField<Nothing>() {
        /** Stable, identity-free rendering so logs / assertions don't leak an identity hash. */
        override fun toString(): String = "Missing"
    }

    /**
     * Sentinel meaning "the field was present and explicitly null".
     *
     * A serializer must emit `"field": null`, not omit the key.
     */
    public object Null : JsonField<Nothing>() {
        /** Stable, identity-free rendering so logs / assertions don't leak an identity hash. */
        override fun toString(): String = "Null"
    }

    /**
     * The field was present and bound cleanly to the declared type. [value] is bounded `T : Any` so
     * a `Known(null)` — which would be indistinguishable from [Null] — cannot be constructed.
     */
    public data class Known<out T : Any>(public val value: T) : JsonField<T>()

    /**
     * The field was present but its JSON shape did not match `T`, captured as a [RawJson] tree.
     *
     * This is the forward-compatibility escape hatch: a deserializer that cannot bind a payload to
     * `T` parks the original JSON here instead of throwing or dropping it, so a read-modify-write
     * round-trips the server's value unchanged.
     */
    public data class Raw(public val json: RawJson) : JsonField<Nothing>()

    /** Returns `true` if this is [Missing]. */
    public val isMissing: Boolean
        get() = this is Missing

    /** Returns `true` if this is [Null]. */
    public val isNull: Boolean
        get() = this is Null

    /** Returns `true` if this is a [Known]. */
    public val isKnown: Boolean
        get() = this is Known<*>

    /** Returns `true` if this is a [Raw]. */
    public val isRaw: Boolean
        get() = this is Raw

    /**
     * Returns [Known.value] when this is a [Known], or `null` for every other variant.
     *
     * Callers that must distinguish [Missing] / [Null] / [Raw] cannot use this — use [fold] or
     * pattern-match the sealed hierarchy directly.
     */
    public fun getOrNull(): T? =
        when (this) {
            is Known -> value
            is Raw, Missing, Null -> null
        }

    /**
     * Folds this four-state field into a value of type [R] by applying the matching lambda.
     *
     * @param onMissing Invoked when this is [Missing].
     * @param onNull    Invoked when this is [Null].
     * @param onKnown   Invoked when this is [Known]; receives [Known.value].
     * @param onRaw     Invoked when this is [Raw]; receives the [RawJson] tree.
     * @return The result of the selected lambda.
     */
    public inline fun <R> fold(
        onMissing: () -> R,
        onNull: () -> R,
        onKnown: (T) -> R,
        onRaw: (RawJson) -> R,
    ): R =
        when (this) {
            Missing -> onMissing()
            Null -> onNull()
            is Known -> onKnown(value)
            is Raw -> onRaw(json)
        }

    /**
     * Collapses this four-state field onto the three-state [Tristate]. **Lossy for [Raw]**: a [Raw]
     * node becomes `Tristate.Present(json)` (the [RawJson] survives, the "wrong type" signal does
     * not). See the class KDoc on the read-path vs. PATCH-path boundary before using this.
     */
    public fun toTristate(): Tristate<Any> =
        when (this) {
            Missing -> Tristate.Absent
            Null -> Tristate.Null
            is Known<*> -> Tristate.Present(value)
            is Raw -> Tristate.Present(json)
        }

    public companion object {
        /** Returns [Missing]. Convenience factory for Java callers / generic call-sites. */
        @JvmStatic
        public fun <T> missing(): JsonField<T> = Missing

        /** Returns [Null]. Convenience factory for Java callers / generic call-sites. */
        @JvmStatic
        @JvmName("nullValue")
        public fun <T> nullValue(): JsonField<T> = Null

        /**
         * Wraps a non-null [value] in a [Known]. Bounded `T : Any`: a `Known(null)` would be an
         * illegal state indistinguishable from [Null]. Use [Null] or [ofNullable] for nullable
         * values.
         */
        @JvmStatic
        public fun <T : Any> known(value: T): JsonField<T> = Known(value)

        /** Wraps a [RawJson] tree in a [Raw]. */
        @JvmStatic
        public fun raw(json: RawJson): JsonField<Nothing> = Raw(json)

        /**
         * Maps a nullable [value] to either [Known] (non-null) or [Null] (null). Cannot produce
         * [Missing] (a value passed in has been "seen") nor [Raw] (a typed value is by definition
         * well-shaped).
         */
        @JvmStatic
        public fun <T : Any> ofNullable(value: T?): JsonField<T> = if (value == null) Null else Known(value)

        /**
         * Lifts a [Tristate] into the matching `JsonField`, for the PATCH → read-model direction.
         * Never produces [Raw]: [Tristate.Absent] → [Missing], [Tristate.Null] → [Null],
         * [Tristate.Present] → [Known].
         */
        @JvmStatic
        public fun <T : Any> fromTristate(tristate: Tristate<T>): JsonField<T> =
            when (tristate) {
                Tristate.Absent -> Missing
                Tristate.Null -> Null
                is Tristate.Present -> Known(tristate.value)
            }
    }
}
