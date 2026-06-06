/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.generics

/**
 * Common builder shape implemented by every SDK builder.
 *
 * The SDK's public models use the "immutable data + private constructor + mutable builder" pattern;
 * builders that participate in generic pipeline composition implement this interface so callers can
 * write helpers that accept any builder. The covariant `out T` makes `Builder<Request>` assignable
 * to `Builder<Any>` without an explicit cast, which is what builder-folding pipeline steps rely on.
 */
public interface Builder<out T> {
    /** Materialize the immutable target value from the builder's current state. */
    public fun build(): T
}
