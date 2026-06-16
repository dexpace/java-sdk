/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.generics

/**
 * Asserts that a required builder field [value] has been set, returning it non-null.
 *
 * Builders that materialize an immutable value in [Builder.build] use this to validate
 * mandatory inputs in one uniform place. When [value] is `null` it throws
 * [IllegalStateException] with the message `"<name> is required"`, where [name] is the
 * caller-supplied field name (typically the builder property, e.g. `"method"`); when
 * [value] is present it is returned as a non-null reference so the result can be assigned
 * directly:
 *
 * ```
 * override fun build(): Request =
 *     Request(
 *         method = checkRequired("method", method),
 *         url = checkRequired("url", url),
 *         // ...
 *     )
 * ```
 *
 * Replacing the ad-hoc `checkNotNull(field) { "Field is required." }` calls scattered
 * across builders with this helper keeps the missing-field diagnostics identical, which
 * matters most once builders are emitted by codegen.
 *
 * @param name The field name to report in the failure message.
 * @param value The field value to validate.
 * @return [value], guaranteed non-null.
 * @throws IllegalStateException If [value] is `null`.
 */
public fun <T : Any> checkRequired(
    name: String,
    value: T?,
): T = checkNotNull(value) { "$name is required" }
