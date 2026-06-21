/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.util.Arrays
import java.util.Objects

/**
 * Deep, structural value-equality helpers for value types that hold arrays.
 *
 * The JDK's [java.util.Objects.equals] and [java.util.Objects.hashCode] compare arrays by
 * **identity**, so two structurally-equal `ByteArray` fields (or any array-typed field) are
 * reported unequal. A value type generated for a DTO with array fields therefore cannot lean
 * on `Objects.equals`/`Objects.hashCode` for a correct `equals`/`hashCode`.
 *
 * These helpers compare any value **by content**: arrays — including primitive arrays, nested
 * object arrays, and arbitrarily-deep multi-dimensional arrays — are compared element-by-element,
 * while non-array values fall back to ordinary [Any.equals]/[Any.hashCode]. [contentEquals] and
 * [contentHashCode] are mutually consistent: whenever `contentEquals(a, b)` is `true`,
 * `contentHashCode(a) == contentHashCode(b)`.
 *
 * Typical use from a hand-written (or later generated) value type:
 * ```kotlin
 * override fun equals(other: Any?): Boolean {
 *     if (this === other) return true
 *     if (other !is Foo) return false
 *     return ValueEquality.contentEquals(payload, other.payload) &&
 *         ValueEquality.contentEquals(matrix, other.matrix)
 * }
 *
 * override fun hashCode(): Int {
 *     var result = ValueEquality.contentHashCode(payload)
 *     result = 31 * result + ValueEquality.contentHashCode(matrix)
 *     return result
 * }
 * ```
 *
 * No `sdk-core` type depends on these helpers today: they are published as a deliberately public,
 * forward-looking primitive that a hand-written value type — or, later, a DTO generator — targets
 * directly for its array-typed fields. The public surface is intentional, not incidental.
 *
 * Kotlin's unsigned arrays (`UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`) are **not**
 * recognized as arrays: boxed to `Any?` they are value-class boxes, not JVM primitive arrays, so
 * they fall through to identity-based `equals`/`hashCode` and will not compare by content. Convert
 * to the signed counterpart (`asByteArray()`, `asIntArray()`, …) for any field that needs content
 * semantics.
 *
 * Both methods are null-safe: two `null` references are equal and hash to `0`.
 */
public object ValueEquality {
    /**
     * Returns `true` if [a] and [b] are equal by content.
     *
     * - Two `null` references are equal; a `null` and a non-`null` are not.
     * - If both are arrays of the same kind, they are compared element-by-element. Object
     *   arrays recurse, so nested and multi-dimensional arrays are compared structurally;
     *   primitive arrays are compared by their element values.
     * - An object array and a primitive array (e.g. `Array<Int>` vs `IntArray`) are **not**
     *   equal even with matching values, mirroring the JVM's distinct array types.
     * - Floating-point elements follow `Arrays.equals`/`Double.equals` semantics rather than
     *   `==`: two `NaN`s compare **equal**, while `0.0` and `-0.0` (and `0.0f`/`-0.0f`) compare
     *   **unequal**. This holds for both primitive (`DoubleArray`/`FloatArray`) and boxed
     *   (`Array<Double>`/`Array<Float>`) arrays, and [contentHashCode] hashes to match.
     * - Any other value is compared with [Any.equals].
     *
     * This is exactly the contract of `java.util.Objects.deepEquals`, to which it delegates.
     *
     * It is a thin wrapper over that JDK method, kept so it pairs symmetrically with
     * [contentHashCode] — for which the JDK offers no `Objects.deepHashCode` equivalent — so callers
     * reach one discoverable, contract-paired entry point instead of mixing `Objects.deepEquals`
     * with a hand-rolled hash.
     */
    @JvmStatic
    public fun contentEquals(
        a: Any?,
        b: Any?,
    ): Boolean = Objects.deepEquals(a, b)

    /**
     * Returns a content-based hash code for [value], consistent with [contentEquals].
     *
     * `null` hashes to `0`. Arrays hash by their content — object arrays recurse so nested
     * and multi-dimensional arrays contribute a deep hash; primitive arrays hash by their
     * element values. Any other value uses its own [Any.hashCode].
     *
     * [contentEquals] delegates to `java.util.Objects.deepEquals`, whereas this method mirrors
     * `java.util.Arrays.deepHashCode` element-wise — the two do not share an implementation and
     * are kept deliberately in lockstep, so any change to either must preserve the contract that
     * content-equal values hash equal.
     */
    @JvmStatic
    public fun contentHashCode(value: Any?): Int =
        when (value) {
            null -> 0
            is Array<*> -> Arrays.deepHashCode(value)
            is BooleanArray -> value.contentHashCode()
            is ByteArray -> value.contentHashCode()
            is CharArray -> value.contentHashCode()
            is ShortArray -> value.contentHashCode()
            is IntArray -> value.contentHashCode()
            is LongArray -> value.contentHashCode()
            is FloatArray -> value.contentHashCode()
            is DoubleArray -> value.contentHashCode()
            else -> value.hashCode()
        }
}
