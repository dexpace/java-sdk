/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

/**
 * Deterministic resolver for the per-operation auth descriptor ladder.
 *
 * Two independent precedence orders are applied, in this order:
 *
 *  1. **Tier precedence** — across the three descriptor tiers, the most specific *present*
 *     descriptor wins outright: per-call override > operation default > client default
 *     (see [AuthDescriptorTier]). A lower tier is consulted only when every higher tier is
 *     absent (`null`). The descriptor chosen this way is the only one resolved against —
 *     a per-call descriptor that cannot be satisfied does **not** fall through to the
 *     operation descriptor; it fails, because the caller asked for that override explicitly.
 *
 *  2. **Requirement precedence** — within the chosen descriptor, [AuthDescriptor.requirements]
 *     are tried in declared order and the first one whose scheme is *satisfiable* is returned.
 *     [AuthScheme.NO_AUTH] is always satisfiable (anonymous access); any other scheme is
 *     satisfiable iff it is in the supplied `availableSchemes` set.
 *
 * The resolver is **scheme-agnostic**: it never inspects a concrete [Credential] or knows how
 * a scheme is stamped onto the wire. The caller supplies the set of schemes it can satisfy
 * (typically derived from the credentials configured on the client), and the resolver returns
 * the [AuthRequirement] to apply — mapping that requirement to a credential and an auth step
 * stays with the caller / adapter. Per-cloud and OAuth specifics never reach core.
 *
 * Stateless and therefore safe for concurrent use; the single shared [INSTANCE] is the
 * intended entry point.
 */
public class AuthDescriptorResolver {
    /**
     * Resolves the descriptor ladder against [availableSchemes].
     *
     * At least one of [perCall], [operation], or [client] must be non-null. The most specific
     * present descriptor is selected by tier precedence, then its requirements are matched in
     * declared order.
     *
     * @param availableSchemes the schemes the caller can satisfy (e.g. from configured
     *   credentials). [AuthScheme.NO_AUTH] need not be present — it is always satisfiable.
     * @param perCall the highest-precedence descriptor attached to this single call, or null.
     * @param operation the descriptor declared by the operation, or null.
     * @param client the client-wide default descriptor, or null.
     * @return the resolved [AuthResolution] — the requirement to apply and the tier it came from.
     * @throws IllegalArgumentException if all three descriptors are null.
     * @throws AuthResolutionException if the selected descriptor lists no satisfiable scheme.
     */
    @JvmOverloads
    public fun resolve(
        availableSchemes: Set<AuthScheme>,
        perCall: AuthDescriptor? = null,
        operation: AuthDescriptor? = null,
        client: AuthDescriptor? = null,
    ): AuthResolution {
        val (descriptor, tier) =
            selectDescriptor(perCall, operation, client)
                ?: throw IllegalArgumentException(
                    "at least one of perCall, operation, or client descriptor must be supplied",
                )

        val match =
            descriptor.requirements.firstOrNull { isSatisfiable(it.scheme, availableSchemes) }
                ?: throw AuthResolutionException(descriptor.schemes, availableSchemes)

        return AuthResolution(match, tier)
    }

    private fun selectDescriptor(
        perCall: AuthDescriptor?,
        operation: AuthDescriptor?,
        client: AuthDescriptor?,
    ): Pair<AuthDescriptor, AuthDescriptorTier>? =
        when {
            perCall != null -> perCall to AuthDescriptorTier.PER_CALL
            operation != null -> operation to AuthDescriptorTier.OPERATION
            client != null -> client to AuthDescriptorTier.CLIENT
            else -> null
        }

    private fun isSatisfiable(
        scheme: AuthScheme,
        availableSchemes: Set<AuthScheme>,
    ): Boolean = scheme == AuthScheme.NO_AUTH || scheme in availableSchemes

    public companion object {
        /** Shared stateless resolver instance. */
        @JvmField
        public val INSTANCE: AuthDescriptorResolver = AuthDescriptorResolver()
    }
}
