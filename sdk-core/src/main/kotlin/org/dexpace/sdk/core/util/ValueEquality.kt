/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.util.Arrays

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
     * - Any other value is compared with [Any.equals].
     */
    @JvmStatic
    public fun contentEquals(
        a: Any?,
        b: Any?,
    ): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return when {
            a is Array<*> && b is Array<*> -> Arrays.deepEquals(a, b)
            a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            a is CharArray && b is CharArray -> a.contentEquals(b)
            a is ShortArray && b is ShortArray -> a.contentEquals(b)
            a is IntArray && b is IntArray -> a.contentEquals(b)
            a is LongArray && b is LongArray -> a.contentEquals(b)
            a is FloatArray && b is FloatArray -> a.contentEquals(b)
            a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
            else -> a == b
        }
    }

    /**
     * Returns a content-based hash code for [value], consistent with [contentEquals].
     *
     * `null` hashes to `0`. Arrays hash by their content — object arrays recurse so nested
     * and multi-dimensional arrays contribute a deep hash; primitive arrays hash by their
     * element values. Any other value uses its own [Any.hashCode].
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
