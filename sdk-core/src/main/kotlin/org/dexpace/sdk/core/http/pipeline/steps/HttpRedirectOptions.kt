/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Method
import java.util.EnumSet

/**
 * Predicate that decides whether a redirect should be followed for a given [HttpRedirectCondition].
 *
 * Kotlin callers may pass a lambda; Java callers may use a lambda or implement this interface.
 */
public fun interface HttpRedirectPredicate {
    /**
     * Returns `true` if the redirect described by [condition] should be followed.
     */
    public fun shouldRedirect(condition: HttpRedirectCondition): Boolean
}

/**
 * Configuration for [DefaultRedirectStep].
 *
 * Defaults mirror Azure Core's redirect policy:
 *  - [maxHops] = 3 hops before the step returns the last response without throwing.
 *  - [allowedMethods] = `{GET, HEAD}` — only idempotent methods follow redirects by default,
 *    preventing accidental re-issue of `POST` against a different URI.
 *  - [locationHeader] = `Location` — overridable for service-specific custom headers.
 *  - [follow303] = `false` — 303 (See Other) is NOT followed by default. Opt-in to
 *    RFC 7231-compliant behavior (re-issue as `GET`, drop body).
 *  - [allowSchemeDowngrade] = `false` — HTTPS → HTTP redirects throw
 *    [IllegalStateException]. Opt-in switches to a WARNING log and lets the downgrade
 *    proceed. Credentials are still stripped before reissue regardless of this flag.
 *
 * Pass a non-null [shouldRedirect] to fully override the built-in decision logic; the
 * default predicate inspects status, allowed method, presence of [locationHeader], and
 * loop detection.
 */
public class HttpRedirectOptions
    @JvmOverloads
    constructor(
        /**
         * Maximum number of redirect hops followed after the initial request; 0 disables
         * redirect following entirely.
         */
        public val maxHops: Int = 3,
        allowedMethods: Set<Method> = EnumSet.of(Method.GET, Method.HEAD),
        public val locationHeader: HttpHeaderName = HttpHeaderName.LOCATION,
        public val follow303: Boolean = false,
        public val allowSchemeDowngrade: Boolean = false,
        public val shouldRedirect: HttpRedirectPredicate? = null,
    ) {
        /**
         * Methods that follow a redirect. Stored as a single defensive copy (a fresh [EnumSet]),
         * exposed as a read-only [Set], so a later mutation of the caller-supplied set cannot
         * change the configured policy after construction.
         */
        public val allowedMethods: Set<Method> = allowedMethods.toCollection(EnumSet.noneOf(Method::class.java))

        /**
         * Returns a new [Builder] pre-filled with every field from this instance. Mutating the
         * builder never affects this [HttpRedirectOptions].
         */
        public fun newBuilder(): Builder =
            Builder().apply {
                maxHops(this@HttpRedirectOptions.maxHops)
                allowedMethods(this@HttpRedirectOptions.allowedMethods)
                locationHeader(this@HttpRedirectOptions.locationHeader)
                follow303(this@HttpRedirectOptions.follow303)
                allowSchemeDowngrade(this@HttpRedirectOptions.allowSchemeDowngrade)
                shouldRedirect(this@HttpRedirectOptions.shouldRedirect)
            }

        /**
         * Builder for [HttpRedirectOptions]. All fields default to the same values as the primary
         * constructor. Obtain via [HttpRedirectOptions.builder].
         */
        public class Builder : org.dexpace.sdk.core.generics.Builder<HttpRedirectOptions> {
            private var maxHops: Int = 3
            private var allowedMethods: Set<Method> = EnumSet.of(Method.GET, Method.HEAD)
            private var locationHeader: HttpHeaderName = HttpHeaderName.LOCATION
            private var follow303: Boolean = false
            private var allowSchemeDowngrade: Boolean = false
            private var shouldRedirect: HttpRedirectPredicate? = null

            /** Sets the maximum number of redirect hops followed after the initial request. */
            public fun maxHops(value: Int): Builder = apply { maxHops = value }

            /** Sets the methods that follow a redirect. A defensive copy is taken at construction. */
            public fun allowedMethods(value: Set<Method>): Builder = apply { allowedMethods = value }

            /** Sets the response header from which the redirect location is read. */
            public fun locationHeader(value: HttpHeaderName): Builder = apply { locationHeader = value }

            /**
             * When `true`, 303 responses are followed (re-issued as `GET`, body dropped).
             * Defaults to `false`.
             */
            public fun follow303(value: Boolean): Builder = apply { follow303 = value }

            /**
             * When `true`, HTTPS → HTTP scheme downgrades are permitted (with a warning log).
             * Defaults to `false` (downgrades throw).
             */
            public fun allowSchemeDowngrade(value: Boolean): Builder = apply { allowSchemeDowngrade = value }

            /**
             * Sets a custom predicate that fully overrides the built-in redirect decision logic.
             * Pass `null` to restore the default predicate.
             */
            public fun shouldRedirect(value: HttpRedirectPredicate?): Builder = apply { shouldRedirect = value }

            override fun build(): HttpRedirectOptions =
                HttpRedirectOptions(
                    maxHops = maxHops,
                    allowedMethods = allowedMethods,
                    locationHeader = locationHeader,
                    follow303 = follow303,
                    allowSchemeDowngrade = allowSchemeDowngrade,
                    shouldRedirect = shouldRedirect,
                )
        }

        public companion object {
            /** Returns a fresh [Builder] with all fields at their defaults. */
            @JvmStatic
            public fun builder(): Builder = Builder()
        }
    }
