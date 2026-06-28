/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

/**
 * Thrown when an [AuthDescriptor] ladder cannot be satisfied: the resolved descriptor lists
 * one or more required schemes, none of which is covered by the available schemes (and the
 * descriptor does not permit anonymous access).
 *
 * The message names the schemes the operation accepts so the caller learns *which* credential
 * to configure — e.g. `operation requires one of [OAUTH2, API_KEY] but no matching credential
 * is available (have: [BASIC])`.
 *
 * @param requiredSchemes the schemes the failed descriptor accepts, in preference order.
 * @param availableSchemes the schemes the caller could satisfy at resolution time.
 */
public class AuthResolutionException(
    requiredSchemes: List<AuthScheme>,
    availableSchemes: Set<AuthScheme>,
) : RuntimeException(buildMessage(requiredSchemes, availableSchemes)) {
    /** The schemes the failed descriptor accepts; a defensive copy. */
    public val requiredSchemes: List<AuthScheme> =
        requiredSchemes.toList()

    /** The schemes the caller could satisfy; a defensive copy. */
    public val availableSchemes: Set<AuthScheme> =
        availableSchemes.toSet()

    private companion object {
        private fun buildMessage(
            required: List<AuthScheme>,
            available: Set<AuthScheme>,
        ): String =
            "operation requires one of $required but no matching credential is available " +
                "(have: $available)"
    }
}
