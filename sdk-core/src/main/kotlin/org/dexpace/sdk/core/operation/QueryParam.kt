/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

/**
 * A single query-string entry contributed by an [OperationParams].
 *
 * Modeled as one ordered `name`/`value` pair so a repeated parameter (`?tag=a&tag=b`) is just
 * two [QueryParam] entries with the same [name] — the projection preserves order and repetition.
 * [value] is the raw, *unencoded* value; the projector ([RequestProjector]) percent-encodes both
 * name and value when it appends them to the URL.
 *
 * A `null` [value] projects a bare, valueless parameter (`?flag`) rather than `?flag=`.
 *
 * Immutable; freely shareable. This is the stop-gap query representation the projection uses
 * until the dedicated `QueryParams` multimap lands; it deliberately stays a flat ordered pair so
 * migrating to that multimap is a mechanical change.
 *
 * @property name Raw parameter name; percent-encoded by the projector.
 * @property value Raw parameter value, or `null` for a valueless parameter.
 */
public data class QueryParam(
    val name: String,
    val value: String?,
)
