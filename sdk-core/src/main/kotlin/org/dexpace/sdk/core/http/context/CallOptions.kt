/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.auth.Credential

/**
 * Immutable, per-call options bag threaded through the context promotion chain
 * ([DispatchContext] -> [RequestContext] -> [ExchangeContext]).
 *
 * `CallOptions` lets a caller override selected client-level behaviour for a *single* call
 * without mutating the shared client: a per-call timeout overlay, a response-validation
 * decision, an ad-hoc credential, and an open set of typed extension [attributes]. The
 * client supplies a baseline; the call site supplies overrides; [applyDefaults] merges the
 * two with per-field precedence (the receiver wins, falling back to the supplied defaults).
 *
 * ## "Unset" without nullability
 *
 * The well-known knobs are non-`null` with an inherit-shaped default — [CallTimeout.NONE] and
 * [ResponseValidation.INHERIT] — so reading them never forces a `!!`. The one genuinely
 * optional field, [credentialOverride], is `null` when absent; that is a real "no override"
 * absence and is read null-safely, not with `!!`.
 *
 * ## Merge semantics
 *
 * [applyDefaults] resolves each field independently:
 *  - [timeout] overlays per phase via [CallTimeout.applyDefaults].
 *  - [responseValidation] coalesces [ResponseValidation.INHERIT] to the default.
 *  - [credentialOverride] takes the receiver's value when present, else the default's.
 *  - [attributes] is a union with the receiver's entries winning on key collisions.
 *
 * [NONE] (every field at its inherit default, no attributes) is the identity element:
 * `x.applyDefaults(NONE) == x` and `NONE.applyDefaults(x) == x`.
 *
 * ## Thread-safety
 *
 * Immutable; the attribute map is defensively copied at build time and exposed read-only.
 * Instances are freely shareable. [Builder] is single-thread / externally guarded.
 *
 * @property timeout Per-phase timeout overlay; [CallTimeout.NONE] inherits every phase.
 * @property responseValidation Response-validation override; [ResponseValidation.INHERIT] defers to the client.
 * @property credentialOverride Ad-hoc credential for this call, or `null` to use the client credential.
 * @property attributes Read-only typed extension overrides keyed by [CallOption].
 */
@ConsistentCopyVisibility
public data class CallOptions private constructor(
    val timeout: CallTimeout,
    val responseValidation: ResponseValidation,
    val credentialOverride: Credential?,
    val attributes: Map<CallOption<*>, Any>,
) {
    /**
     * Returns `true` when this option set overrides nothing — every field is at its inherit
     * default and no extension attributes are present. Equivalent to `this == NONE`.
     */
    public fun isEmpty(): Boolean =
        timeout.isEmpty() &&
            responseValidation == ResponseValidation.INHERIT &&
            credentialOverride == null &&
            attributes.isEmpty()

    /**
     * Returns the value stored under [key], or `null` if absent. The result is typed to the
     * key's value type — no cast at the call site.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> get(key: CallOption<T>): T? = attributes[key] as T?

    /**
     * Returns the value stored under [key], or [fallback] if absent.
     */
    public fun <T : Any> getOrDefault(
        key: CallOption<T>,
        fallback: T,
    ): T = get(key) ?: fallback

    /**
     * Returns `true` if a value is present under [key].
     */
    public fun contains(key: CallOption<*>): Boolean = attributes.containsKey(key)

    /**
     * Overlays this option set onto [defaults], resolving each field independently (see the
     * type KDoc for the per-field rules). The receiver always has priority; [defaults] fills
     * in whatever the receiver leaves at its inherit value.
     *
     * @param defaults The lower-priority option set.
     * @return A merged option set; returns `this` unchanged when the merge produces no difference.
     */
    public fun applyDefaults(defaults: CallOptions): CallOptions {
        val mergedAttributes =
            if (defaults.attributes.isEmpty()) {
                attributes
            } else {
                LinkedHashMap<CallOption<*>, Any>(defaults.attributes).apply { putAll(attributes) }
            }
        val merged =
            CallOptions(
                timeout = timeout.applyDefaults(defaults.timeout),
                responseValidation = responseValidation.applyDefault(defaults.responseValidation),
                credentialOverride = credentialOverride ?: defaults.credentialOverride,
                attributes = mergedAttributes,
            )
        return if (merged == this) this else merged
    }

    /**
     * Returns a new [Builder] pre-filled with this option set's fields.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Mutable builder for [CallOptions]. Implements [org.dexpace.sdk.core.generics.Builder] so
     * it composes with builder-folding helpers.
     */
    public class Builder : org.dexpace.sdk.core.generics.Builder<CallOptions> {
        private var timeout: CallTimeout = CallTimeout.NONE
        private var responseValidation: ResponseValidation = ResponseValidation.INHERIT
        private var credentialOverride: Credential? = null
        private val attributes: LinkedHashMap<CallOption<*>, Any> = LinkedHashMap()

        /** Creates an empty builder (every field inherits). */
        public constructor()

        /** Creates a builder initialized from [options]. */
        public constructor(options: CallOptions) {
            this.timeout = options.timeout
            this.responseValidation = options.responseValidation
            this.credentialOverride = options.credentialOverride
            this.attributes.putAll(options.attributes)
        }

        /** Sets the per-phase timeout overlay. Defaults to [CallTimeout.NONE]. */
        public fun timeout(timeout: CallTimeout): Builder = apply { this.timeout = timeout }

        /** Sets the response-validation override. Defaults to [ResponseValidation.INHERIT]. */
        public fun responseValidation(responseValidation: ResponseValidation): Builder =
            apply { this.responseValidation = responseValidation }

        /** Sets the ad-hoc credential override. Pass `null` to use the client credential. */
        public fun credentialOverride(credentialOverride: Credential?): Builder =
            apply { this.credentialOverride = credentialOverride }

        /**
         * Stores [value] under the typed [key], replacing any previous value for that key.
         */
        public fun <T : Any> attribute(
            key: CallOption<T>,
            value: T,
        ): Builder = apply { attributes[key] = value }

        /**
         * Removes any value previously stored under [key].
         */
        public fun removeAttribute(key: CallOption<*>): Builder = apply { attributes.remove(key) }

        /**
         * Builds the [CallOptions], taking a defensive, read-only copy of the attribute map.
         */
        override fun build(): CallOptions =
            CallOptions(
                timeout = timeout,
                responseValidation = responseValidation,
                credentialOverride = credentialOverride,
                attributes = if (attributes.isEmpty()) emptyMap() else LinkedHashMap(attributes),
            )
    }

    public companion object {
        /** The empty option set: every field inherits and no attributes are present. */
        @JvmField
        public val NONE: CallOptions =
            CallOptions(
                timeout = CallTimeout.NONE,
                responseValidation = ResponseValidation.INHERIT,
                credentialOverride = null,
                attributes = emptyMap(),
            )

        /** Returns a fresh empty builder. Java-friendly `CallOptions.builder()` entry point. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
