/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import java.util.Collections
import org.dexpace.sdk.core.generics.Builder as GenericBuilder

/**
 * A single auth alternative an operation accepts: one [AuthScheme] plus any OAuth-specific
 * parameters ([oauthScopes] / [oauthParams]) the auth step should forward to the token
 * provider when the chosen scheme is [AuthScheme.OAUTH2].
 *
 * Each requirement pairs a scheme with its *own* OAuth parameters, so an operation that
 * accepts OAuth with `read` scopes **or** a static API key is two requirements, only one of
 * which carries scopes. An ordered list of these, in preference order, is the building block
 * of an [AuthDescriptor].
 *
 * The OAuth collections are meaningful only for [AuthScheme.OAUTH2]; for any other scheme
 * they are ignored by the resolution path but still defensively copied and exposed so a
 * caller can inspect what was declared.
 *
 * Immutable: each collection is copied on the way in and exposed as an unmodifiable view, so
 * a caller that retains and later mutates the argument collection cannot mutate this instance.
 * Instances are obtained via [of] or [Builder], never a public constructor.
 *
 * @param scheme the auth scheme this alternative selects.
 * @param oauthScopes OAuth scopes to forward to the token provider; ignored for non-OAuth schemes.
 * @param oauthParams extra OAuth params to forward (e.g. `claims`); ignored for non-OAuth schemes.
 */
public class AuthRequirement private constructor(
    scheme: AuthScheme,
    oauthScopes: List<String>,
    oauthParams: Map<String, Any>,
) {
    /** The auth scheme this alternative selects. */
    public val scheme: AuthScheme = scheme

    /** OAuth scopes to forward to the token provider; an unmodifiable defensive copy. */
    public val oauthScopes: List<String> = Collections.unmodifiableList(oauthScopes.toList())

    /** Extra OAuth params to forward; an unmodifiable defensive copy. */
    public val oauthParams: Map<String, Any> = Collections.unmodifiableMap(oauthParams.toMap())

    /** A [Builder] pre-filled with this requirement's state. */
    public fun newBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthRequirement) return false
        return scheme == other.scheme &&
            oauthScopes == other.oauthScopes &&
            oauthParams == other.oauthParams
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + oauthScopes.hashCode()
        result = 31 * result + oauthParams.hashCode()
        return result
    }

    override fun toString(): String =
        "AuthRequirement(scheme=$scheme, oauthScopes=$oauthScopes, oauthParams=$oauthParams)"

    /**
     * Mutable builder for [AuthRequirement]. The [scheme] is mandatory; OAuth collections
     * default to empty and are defensively copied on the way in.
     */
    public class Builder() : GenericBuilder<AuthRequirement> {
        private var scheme: AuthScheme? = null
        private var oauthScopes: List<String> = emptyList()
        private var oauthParams: Map<String, Any> = emptyMap()

        /** Pre-fills the builder with an existing [requirement]'s state. */
        public constructor(requirement: AuthRequirement) : this() {
            scheme = requirement.scheme
            oauthScopes = requirement.oauthScopes
            oauthParams = requirement.oauthParams
        }

        /** Sets the auth scheme (required). */
        public fun scheme(scheme: AuthScheme): Builder =
            apply {
                this.scheme = scheme
            }

        /** Replaces the OAuth scopes with a defensive copy of [oauthScopes]. */
        public fun oauthScopes(oauthScopes: List<String>): Builder =
            apply {
                this.oauthScopes = oauthScopes.toList()
            }

        /** Replaces the OAuth params with a defensive copy of [oauthParams]. */
        public fun oauthParams(oauthParams: Map<String, Any>): Builder =
            apply {
                this.oauthParams = oauthParams.toMap()
            }

        override fun build(): AuthRequirement =
            AuthRequirement(
                scheme = checkNotNull(scheme) { "scheme is required" },
                oauthScopes = oauthScopes,
                oauthParams = oauthParams,
            )
    }

    public companion object {
        /**
         * A requirement for [scheme], optionally carrying OAuth [oauthScopes] / [oauthParams]
         * (both default to empty and are meaningful only for [AuthScheme.OAUTH2]).
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            scheme: AuthScheme,
            oauthScopes: List<String> = emptyList(),
            oauthParams: Map<String, Any> = emptyMap(),
        ): AuthRequirement = AuthRequirement(scheme, oauthScopes, oauthParams)
    }
}
