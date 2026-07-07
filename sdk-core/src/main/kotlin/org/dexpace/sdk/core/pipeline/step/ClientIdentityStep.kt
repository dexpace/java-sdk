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
 * - [Mode.Append] (default): if the chosen header already carries one or more values, the SDK
 *   token line is appended to the first value after a single space, and every other pre-existing
 *   value is preserved. This keeps caller-set identifiers such as `MyApp/1.0` intact, producing
 *   `MyApp/1.0 dexpace-sdk/X jvm/Y`. If the header is absent the token line is set as the sole
 *   value.
 * - [Mode.Replace]: any existing header value(s) are overwritten with the SDK token
 *   line. Use when the SDK is the sole producer of the header and a caller-set value
 *   would conflict with fleet conventions.
 *
 * ## Empty or blank token lists
 *
 * If the configured token list is empty — or every token is blank, so the joined line is blank —
 * the step is a no-op and the request is returned unchanged. The step deliberately never emits a
 * blank or whitespace-only header (an empty `User-Agent` is a wire-protocol violation, and a blank
 * custom header carries no information).
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
         * The single public way to apply this step's transform: emits the composite
         * client-identity token line onto [input] and returns the resulting request. [context]
         * is unused — the transform depends only on the request's existing headers.
         *
         * Resolution rules:
         *  - empty or all-blank tokens -> no change (request returned unchanged)
         *  - header absent             -> header set to the joined token line
         *  - header present + Append   -> joined line appended to the first existing value; all
         *                                 other pre-existing values preserved
         *  - header present + Replace  -> all existing values replaced with the joined line
         */
        override fun execute(
            input: Request,
            context: DispatchContext,
        ): Request = applyTokens(input)

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

        /** Private transform carrying the full step logic; the sole caller is [execute]. */
        private fun applyTokens(request: Request): Request {
            if (tokens.isEmpty()) return request

            val tokenLine = tokens.joinToString(" ")
            // A list of blank/whitespace-only tokens joins to a blank line; emitting it would write a
            // useless (and, for User-Agent, protocol-violating) blank header. Treat it as a no-op.
            if (tokenLine.isBlank()) return request

            // Read ALL existing values, not just the first: reading only get() (the first value) then
            // replacing with a single value would silently drop any additional pre-existing values.
            val existingValues: List<String> = request.headers.values(headerName)

            val newValues: List<String> =
                when {
                    existingValues.isEmpty() -> listOf(tokenLine)
                    mode == Mode.Replace -> listOf(tokenLine)
                    else -> {
                        // Append the token line to the first existing value and keep every other
                        // value intact. An empty first value is treated as "absent" (no leading space).
                        val head = existingValues.first()
                        val appendedHead = if (head.isEmpty()) tokenLine else "$head $tokenLine"
                        listOf(appendedHead) + existingValues.drop(1)
                    }
                }

            return request
                .newBuilder()
                .setHeader(headerName, newValues)
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
