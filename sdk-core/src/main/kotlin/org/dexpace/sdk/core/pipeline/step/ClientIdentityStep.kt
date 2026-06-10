/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.generics.Builder
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.util.SdkInfo

/**
 * [RequestPipelineStep] that emits a composite client-identity token line onto an outgoing
 * request — by default `dexpace-sdk/<sdkver> jvm/<javaver>` on the `User-Agent` header.
 *
 * The shape mirrors gax's `ApiClientHeaderProvider`: a single header value composed of
 * space-separated `<name>/<version>` tokens so that fleet observability can identify the
 * SDK, the JVM, and any plugged-in transport (e.g. `okhttp/5.0.0`) at a glance. See
 * `docs/refs-comparison.md` § "Instrumentation & Tracing" item 4.
 *
 * ## Modes
 *
 * - [Mode.Append] (default): if the chosen header already carries a value, the SDK token
 *   line is appended after a single space. This preserves caller-set identifiers such as
 *   `MyApp/1.0`, producing `MyApp/1.0 dexpace-sdk/X jvm/Y`. If the header is absent the
 *   token line is set as the sole value.
 * - [Mode.Replace]: the existing header value (if any) is overwritten with the SDK token
 *   line. Use when the SDK is the sole producer of the header and a caller-set value
 *   would conflict with fleet conventions.
 *
 * ## Empty token lists
 *
 * If the configured token list is empty, the step is a no-op — the request is returned
 * unchanged. The step deliberately does not emit a blank header in this case (an empty
 * `User-Agent` is a wire-protocol violation, and an empty custom header carries no
 * information).
 *
 * ## Thread-safety
 *
 * Instances are immutable. The token list is captured at construction and never mutated
 * thereafter. Safe to share across concurrent requests.
 *
 * ## Java interop
 *
 * Both the public constructor and the builder's `addToken` overloads carry
 * [JvmOverloads] so that Java callers can elide trailing arguments without supplying
 * defaults.
 */
public class ClientIdentityStep
    @JvmOverloads
    public constructor(
        tokens: List<String> = SdkInfo.defaultTokens(),
        private val headerName: String = DEFAULT_HEADER_NAME,
        private val mode: Mode = Mode.Append,
    ) : RequestPipelineStep {
        /**
         * Ordered token list, frozen at construction. A defensive copy is taken so caller
         * mutations to the source list do not bleed into this step's emitted header value.
         */
        private val tokens: List<String> = tokens.toList()

        /**
         * How the step should reconcile its token line with an existing value on the
         * configured header.
         */
        public enum class Mode {
            /** Append the SDK token line after the existing value, separated by a space. */
            Append,

            /** Replace any existing value with the SDK token line. */
            Replace,
        }

        /**
         * Pipeline entry point — delegates to [apply], which carries the full step logic and
         * is also the Java-friendly direct-call form.
         */
        override fun execute(
            input: Request,
            context: DispatchContext,
        ): Request = apply(input)

        /**
         * Returns a [ClientIdentityStepBuilder] pre-filled with this instance's token list,
         * header name, and [Mode] so callers can derive a tweaked step without re-stating
         * every field. Mirrors the `newBuilder()` convention on
         * [IdempotencyKeyStep][org.dexpace.sdk.core.pipeline.step.IdempotencyKeyStep] and
         * [RetrySettings][org.dexpace.sdk.core.pipeline.step.retry.RetrySettings].
         *
         * The pre-filled tokens are treated as caller-owned: a subsequent
         * [ClientIdentityStepBuilder.addToken] appends to them rather than discarding the
         * default seed (there is no seed to discard once an instance has been materialised).
         *
         * @return A new builder carrying this instance's configuration.
         */
        public fun newBuilder(): ClientIdentityStepBuilder = ClientIdentityStepBuilder(this)

        /**
         * Applies the step to [request] and returns the resulting request. If the
         * configured token list is empty the request is returned unchanged.
         *
         * Resolution rules:
         *  - empty tokens          -> no change
         *  - header absent         -> header set to the joined token line
         *  - header present + Append  -> header set to `existing + " " + joined`
         *  - header present + Replace -> header set to joined
         */
        public fun apply(request: Request): Request {
            if (tokens.isEmpty()) return request

            val tokenLine = tokens.joinToString(" ")
            val existing = request.headers.get(headerName)

            val newValue =
                when {
                    existing.isNullOrEmpty() -> tokenLine
                    mode == Mode.Replace -> tokenLine
                    else -> "$existing $tokenLine"
                }

            return request
                .newBuilder()
                .setHeader(headerName, newValue)
                .build()
        }

        /**
         * Mutable builder for [ClientIdentityStep]. Implements [Builder] so it can plug
         * into builder-folding pipelines.
         *
         * Defaults: token list seeded with [SdkInfo.defaultTokens] (i.e.
         * `dexpace-sdk/<ver>`, `jvm/<javaver>`); header name `User-Agent`; mode
         * [Mode.Append]. Any of these can be overridden by calling the corresponding
         * setter before [build].
         *
         * Calling [tokens] with a list, or the first call to [addToken], replaces the
         * default seed. Once any explicit token has been added the seed is discarded —
         * this avoids accidentally double-emitting the default identifiers when the
         * caller wants a fully custom token line.
         */
        public class ClientIdentityStepBuilder : Builder<ClientIdentityStep> {
            private val tokens: MutableList<String> = SdkInfo.defaultTokens().toMutableList()
            private var seeded: Boolean = false
            private var headerName: String = DEFAULT_HEADER_NAME
            private var mode: Mode = Mode.Append

            /** Creates an empty builder seeded with the documented defaults. */
            public constructor()

            /**
             * Creates a builder pre-filled with the configuration of [step]. The copied
             * token list is treated as caller-owned (the default seed is considered already
             * discarded), so a subsequent [addToken] appends to it rather than clearing it.
             *
             * @param step The step whose configuration to copy.
             */
            public constructor(step: ClientIdentityStep) {
                this.tokens.clear()
                this.tokens.addAll(step.tokens)
                this.seeded = true
                this.headerName = step.headerName
                this.mode = step.mode
            }

            /**
             * Appends a single token to the end of the ordered list. Caller responsibility
             * to pre-format the token (e.g. `"okhttp/5.0.0"`).
             */
            public fun addToken(token: String): ClientIdentityStepBuilder =
                apply {
                    discardSeedIfNeeded()
                    tokens.add(token)
                }

            /**
             * Convenience overload for the common `<name>/<version>` shape. Equivalent to
             * `addToken("$name/$version")`.
             */
            public fun addToken(
                name: String,
                version: String,
            ): ClientIdentityStepBuilder = addToken("$name/$version")

            /**
             * Replaces the current token list wholesale. Useful for fully custom identity
             * lines that do not include the default `dexpace-sdk/`+`jvm/` pair.
             */
            public fun tokens(tokens: List<String>): ClientIdentityStepBuilder =
                apply {
                    this.tokens.clear()
                    this.tokens.addAll(tokens)
                    // After explicit `tokens(...)` the list is fully caller-owned — treat
                    // the seed as already discarded so future addToken calls append to the
                    // supplied list rather than re-clearing it.
                    seeded = true
                }

            /**
             * Sets the header to emit the token line on. Defaults to `User-Agent`. Custom
             * headers like `X-Dexpace-Client` are common when the application wants to
             * keep `User-Agent` reserved for application identity.
             */
            public fun headerName(headerName: String): ClientIdentityStepBuilder =
                apply {
                    this.headerName = headerName
                }

            /**
             * Sets the reconciliation [Mode]. Defaults to [Mode.Append].
             */
            public fun mode(mode: Mode): ClientIdentityStepBuilder =
                apply {
                    this.mode = mode
                }

            /**
             * Builds an immutable [ClientIdentityStep] from this builder's current state.
             */
            override fun build(): ClientIdentityStep =
                ClientIdentityStep(
                    tokens = tokens.toList(),
                    headerName = headerName,
                    mode = mode,
                )

            private fun discardSeedIfNeeded() {
                if (!seeded) {
                    tokens.clear()
                    seeded = true
                }
            }
        }

        public companion object {
            /** Header name used when none is supplied: HTTP/1.1 `User-Agent`. */
            public const val DEFAULT_HEADER_NAME: String = "User-Agent"

            /** Returns a fresh [ClientIdentityStepBuilder] for Java-friendly construction. */
            @JvmStatic
            public fun builder(): ClientIdentityStepBuilder = ClientIdentityStepBuilder()
        }
    }
