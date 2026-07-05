/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.type.TypeFactory
import org.dexpace.sdk.core.serde.Tristate

/** The element [JavaType] for `Object` — the fallback when a `Tristate` is raw or `Tristate<*>`. */
internal val ANY_TYPE: JavaType = TypeFactory.defaultInstance().constructType(Any::class.java)

/** The first contained type argument, or `null` when the type is raw / carries no parameters. */
internal fun JavaType.firstContainedOrNull(): JavaType? = if (containedTypeCount() > 0) containedType(0) else null

/** Whether this type is (or extends) [Tristate]. */
internal fun JavaType.isTristate(): Boolean = Tristate::class.java.isAssignableFrom(rawClass)
