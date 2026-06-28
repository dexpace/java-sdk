/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import java.util.Collections
import org.dexpace.sdk.core.generics.Builder as GenericBuilder

/**
 * Per-operation auth descriptor: the ordered set of [AuthRequirement]s an operation accepts,
 * in preference order. The first requirement whose scheme can be satisfied by the available
 * credentials wins (see [AuthDescriptorResolver]).
 *
 * This is the runtime primitive a code generator would later emit one instance of per
 * operation, but it is fully hand-constructable today. A two-boolean "needs-auth /
 * needs-key" model does not generalise to operations that accept several alternative
 * schemes with different OAuth parameters; an ordered [requirements] list does.
 *
 * The descriptor is deliberately **scheme-agnostic**: it records *which* schemes are
 * acceptable and in what order, never how a scheme is stamped onto the wire. Mapping a
 * resolved requirement to a concrete credential and auth step is the resolver's and the
 * caller's job; per-cloud / OAuth specifics stay in adapters.
 *
 * A descriptor that lists [AuthScheme.NO_AUTH] anywhere in [requirements] declares that the
 * operation may be invoked anonymously. [allowsAnonymous] reports this; the resolver treats
 * it as an always-satisfiable terminal alternative.
 *
 * Immutable: [requirements] is copied on the way in and exposed as an unmodifiable view.
 *
 * @param requirements the accepted auth alternatives, in preference order. Must not be empty.
 * @throws IllegalArgumentException if [requirements] is empty.
 */
public class AuthDescriptor private constructor(
    requirements: List<AuthRequirement>,
) {
    /** The accepted auth alternatives, in preference order; an unmodifiable defensive copy. */
    public val requirements: List<AuthRequirement> =
        Collections.unmodifiableList(requirements.toList())

    /** The schemes this operation accepts, in preference order. Convenience over [requirements]. */
    public val schemes: List<AuthScheme>
        get() = requirements.map { it.scheme }

    init {
        require(this.requirements.isNotEmpty()) { "requirements must not be empty" }
    }

    /** True if any requirement is [AuthScheme.NO_AUTH] — the operation may run anonymously. */
    public fun allowsAnonymous(): Boolean = requirements.any { it.scheme == AuthScheme.NO_AUTH }

    /** A [Builder] pre-filled with this descriptor's requirements. */
    public fun newBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthDescriptor) return false
        return requirements == other.requirements
    }

    override fun hashCode(): Int = requirements.hashCode()

    override fun toString(): String = "AuthDescriptor(requirements=$requirements)"

    /**
     * Mutable builder for [AuthDescriptor]. At least one requirement must be added before
     * [build]; requirements are kept in the order they are added.
     */
    public class Builder : GenericBuilder<AuthDescriptor> {
        private val requirements: MutableList<AuthRequirement> = mutableListOf()

        public constructor()

        /** Pre-fills the builder with an existing [descriptor]'s requirements. */
        public constructor(descriptor: AuthDescriptor) {
            requirements.addAll(descriptor.requirements)
        }

        /** Appends a single [requirement] to the preference order. */
        public fun addRequirement(requirement: AuthRequirement): Builder =
            apply {
                requirements.add(requirement)
            }

        /** Appends a single [scheme] (with no OAuth parameters) to the preference order. */
        public fun addScheme(scheme: AuthScheme): Builder =
            apply {
                requirements.add(AuthRequirement.of(scheme))
            }

        /** Replaces all requirements with [requirements], preserving order. */
        public fun requirements(requirements: List<AuthRequirement>): Builder =
            apply {
                this.requirements.clear()
                this.requirements.addAll(requirements)
            }

        override fun build(): AuthDescriptor = AuthDescriptor(requirements)
    }

    public companion object {
        /**
         * Builds a descriptor from [requirements] in the given preference order.
         *
         * @throws IllegalArgumentException if [requirements] is empty.
         */
        @JvmStatic
        public fun of(requirements: List<AuthRequirement>): AuthDescriptor = AuthDescriptor(requirements)

        /**
         * Builds a descriptor from bare [schemes] (no OAuth parameters), in preference order.
         *
         * @throws IllegalArgumentException if [schemes] is empty.
         */
        @JvmStatic
        public fun ofSchemes(schemes: List<AuthScheme>): AuthDescriptor =
            AuthDescriptor(schemes.map { AuthRequirement.of(it) })
    }
}
