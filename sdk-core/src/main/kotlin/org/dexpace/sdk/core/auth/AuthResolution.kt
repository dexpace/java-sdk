/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

/**
 * The outcome of resolving an auth descriptor ladder against the available schemes — the
 * single [AuthRequirement] the auth step should apply, the [AuthDescriptorTier] the winning
 * descriptor came from, and whether the resolution chose the anonymous ([AuthScheme.NO_AUTH])
 * alternative.
 *
 * Value semantics via `data class`: two resolutions are equal when they pick the same
 * requirement from the same tier.
 *
 * @param requirement the chosen auth alternative; its [AuthRequirement.scheme] is the scheme
 *   to apply, and its OAuth parameters are forwarded for [AuthScheme.OAUTH2].
 * @param tier the precedence tier of the descriptor the requirement was resolved from.
 */
public data class AuthResolution(
    val requirement: AuthRequirement,
    val tier: AuthDescriptorTier,
) {
    /** The scheme to apply. Shorthand for `requirement.scheme`. */
    val scheme: AuthScheme
        get() = requirement.scheme

    /** True when the resolved alternative is the anonymous [AuthScheme.NO_AUTH] marker. */
    val isAnonymous: Boolean
        get() = requirement.scheme == AuthScheme.NO_AUTH
}
