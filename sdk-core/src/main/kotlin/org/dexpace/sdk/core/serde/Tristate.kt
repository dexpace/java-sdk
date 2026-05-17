package org.dexpace.sdk.core.serde

/**
 * Three-valued container for fields that must distinguish **absent**, **explicit null**, and
 * **present** at the serialization boundary — primarily HTTP `PATCH` payloads where the wire
 * format treats "leave this property alone" (absent) very differently from "clear this property"
 * (null).
 *
 * Java's `null` and Kotlin's nullable types only encode two states (present-with-value vs.
 * everything-else); they cannot tell `{}` apart from `{"x": null}`. `Tristate` adds the third
 * state explicitly:
 *
 *  - [Absent]  — the property is missing from the wire payload entirely.
 *  - [Null]    — the property is present and explicitly `null`.
 *  - [Present] — the property is present and carries [Present.value].
 *
 * Concrete serializer/deserializer wiring lives in adapter modules (e.g. `sdk-serde-jackson`'s
 * `TristateModule`). `sdk-core` only exposes the abstract sum type so generated DTOs can declare
 * `Tristate<T>` fields without dragging Jackson — or any other library — into the core surface.
 *
 * The class is covariant in `T` so `Present<String>` is assignable to `Tristate<CharSequence>`;
 * [Absent] and [Null] use `Tristate<Nothing>` and therefore satisfy any `Tristate<T>` target
 * without forcing the caller through a cast.
 *
 * Instances are immutable; equality is structural via the data-class variant ([Present]) and
 * referential for the singleton objects ([Absent], [Null]).
 */
public sealed class Tristate<out T> {
    /**
     * Sentinel meaning "the field was not present in the wire payload".
     *
     * Distinct from [Null] — [Absent] tells a `PATCH` server "leave this property unchanged",
     * whereas [Null] tells it "clear this property".
     */
    public object Absent : Tristate<Nothing>()

    /**
     * Sentinel meaning "the field was present and explicitly null".
     *
     * The serializer must emit `"field": null`, not omit the field entirely. Consumers can read
     * this as an explicit clear request in `PATCH` semantics.
     */
    public object Null : Tristate<Nothing>()

    /**
     * Present value carrier. [value] is the deserialized payload — never `null`; use [Null] for
     * the explicit-null case.
     */
    public data class Present<out T>(public val value: T) : Tristate<T>()

    /** Returns `true` if this is [Absent]. */
    public val isAbsent: Boolean
        get() = this is Absent

    /** Returns `true` if this is [Null]. */
    public val isNull: Boolean
        get() = this is Null

    /** Returns `true` if this is a [Present]. */
    public val isPresent: Boolean
        get() = this is Present<*>

    /**
     * Returns [Present.value] when this is a [Present], or `null` for [Absent] / [Null].
     *
     * Note: callers that need to distinguish [Absent] from [Null] cannot use this method —
     * use [fold] or pattern-match on the sealed hierarchy directly.
     */
    public fun getOrNull(): T? =
        when (this) {
            is Present -> value
            Absent, Null -> null
        }

    /**
     * Folds this tri-state into a value of type [R] by applying the matching lambda for the
     * current variant. Mirrors `kotlin.Result.fold` ergonomics so callers can collapse a
     * three-way `when` into a single expression.
     *
     * @param onAbsent  Invoked when this is [Absent]. Receives no value.
     * @param onNull    Invoked when this is [Null]. Receives no value.
     * @param onPresent Invoked when this is [Present]. Receives [Present.value].
     * @return The result of the selected lambda.
     */
    public inline fun <R> fold(
        onAbsent: () -> R,
        onNull: () -> R,
        onPresent: (T) -> R,
    ): R =
        when (this) {
            Absent -> onAbsent()
            Null -> onNull()
            is Present -> onPresent(value)
        }

    public companion object {
        /** Returns [Absent]. Convenience factory for Java callers / generic call-sites. */
        @JvmStatic
        public fun <T> absent(): Tristate<T> = Absent

        /** Returns [Null]. Convenience factory for Java callers / generic call-sites. */
        @JvmStatic
        @JvmName("nullValue")
        public fun <T> nullValue(): Tristate<T> = Null

        /** Wraps [value] in a [Present]. */
        @JvmStatic
        public fun <T> present(value: T): Tristate<T> = Present(value)

        /**
         * Convenience: maps a nullable [value] to either [Present] (non-null) or [Null] (null).
         *
         * This factory **cannot** produce [Absent] — by definition a value passed in has been
         * "seen" by the caller. Use [absent] when the source data has no entry at all.
         */
        @JvmStatic
        public fun <T : Any> ofNullable(value: T?): Tristate<T> =
            if (value == null) Null else Present(value)
    }
}
