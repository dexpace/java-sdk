/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

/**
 * A single path-template substitution contributed by an [OperationParams].
 *
 * [name] matches a `{name}` placeholder in an operation's path template (e.g. `/users/{id}`);
 * [value] is the raw, *unencoded* replacement. The projector ([RequestProjector]) percent-encodes
 * the value for the path segment when it substitutes it, so callers pass plain values.
 *
 * Immutable; freely shareable.
 *
 * @property name Placeholder name, without the surrounding braces.
 * @property value Raw replacement value; percent-encoded by the projector.
 */
public data class PathParam(
    val name: String,
    val value: String,
)
