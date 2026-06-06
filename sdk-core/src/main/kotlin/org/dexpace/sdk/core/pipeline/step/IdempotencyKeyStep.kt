/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.util.Collections
import java.util.EnumSet
import java.util.UUID
import java.util.function.Supplier

/**
 * [RequestPipelineStep] that injects an idempotency key header into outgoing write requests.
 *
 * Modelled on Airbyte's `IdempotencyHook` (`utils/Hook.java:284-292`) but exposed as a
 * first-class pipeline step rather than a `BeforeRequest` hook so it composes with every
 * other [RequestPipelineStep]. See `docs/refs-comparison.md` § Idempotency for the rationale.
 *
 * ## Default behaviour
 *
 * For requests whose [Request.method] is in [methods] (default: `POST`, `PUT`, `PATCH`),
 * the step adds an `Idempotency-Key` header carrying a freshly minted [UUID]. Requests for
 * other methods (`GET`, `HEAD`, `OPTIONS`, etc.) pass through untouched — these are safe by
 * HTTP semantics and the key carries no useful meaning.
 *
 * ## Configuration knobs
 *
 *  - **[header]** — the header name to write. Defaults to `Idempotency-Key`, the de-facto
 *    standard popularised by Stripe and adopted by most payment APIs. Override for vendors
 *    that use a different name (e.g. `X-Request-Id`, `X-Idempotency-Key`).
 *  - **[methods]** — HTTP methods to inject on. Defaults to the canonical write set
 *    (`POST`, `PUT`, `PATCH`). Extend to include `DELETE` for APIs that treat `DELETE` as
 *    idempotency-sensitive (e.g. resource deletion with audit trails).
 *  - **[keyStrategy]** — supplies the key value. Defaults to `UUID.randomUUID().toString()`.
 *    Override for deterministic keys derived from a request hash (some APIs prefer this so
 *    that retries of a logically-identical request collapse on the server). Callers must
 *    ensure the strategy is thread-safe.
 *  - **[respectExisting]** — when `true` (default), an existing header on the request is
 *    preserved and the strategy is not invoked. When `false`, the strategy always runs and
 *    the result overwrites any existing value. The default matches the principle of least
 *    surprise: explicit caller-set headers always win.
 *
 * ## Thread-safety
 *
 * Instances are immutable and safe to share. [keyStrategy] is the caller's responsibility —
 * the default `UUID.randomUUID()` is thread-safe; custom strategies must declare their own
 * threading contract.
 *
 * ## Java interop
 *
 * The Kotlin function type `() -> String` materialises as `kotlin.jvm.functions.Function0`
 * in Java, which is awkward to construct. Use the [Builder.keyStrategy] overload that takes
 * a [Supplier] from a Java caller — it wraps the supplier in a Kotlin lambda internally.
 *
 * @property header Header name to inject. Case-insensitive on the wire.
 * @property methods HTTP methods that trigger injection.
 * @property keyStrategy Function returning the key value. Invoked once per applicable request.
 * @property respectExisting If `true`, a request already carrying [header] is left untouched.
 */
public class IdempotencyKeyStep private constructor(
    public val header: String,
    public val methods: Set<Method>,
    public val keyStrategy: () -> String,
    public val respectExisting: Boolean,
) : RequestPipelineStep {
    override fun execute(
        input: Request,
        context: DispatchContext,
    ): Request {
        if (input.method !in methods) return input
        if (respectExisting && input.headers.contains(header)) return input
        return input
            .newBuilder()
            .setHeader(header, keyStrategy())
            .build()
    }

    /**
     * Returns a [Builder] initialized with this step's current configuration so callers can
     * derive a tweaked instance without re-stating every field.
     *
     * @return A new builder pre-filled with this instance's fields.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Mutable builder for [IdempotencyKeyStep]. Implements the generic
     * [org.dexpace.sdk.core.generics.Builder] contract so the step can participate in
     * builder-folding pipeline composition.
     */
    public class Builder : org.dexpace.sdk.core.generics.Builder<IdempotencyKeyStep> {
        private var header: String = DEFAULT_HEADER
        private var methods: Set<Method> = DEFAULT_METHODS
        private var keyStrategy: () -> String = DEFAULT_KEY_STRATEGY
        private var respectExisting: Boolean = DEFAULT_RESPECT_EXISTING

        /** Creates an empty builder seeded with the documented defaults. */
        public constructor()

        /**
         * Creates a builder initialized with the configuration of [step].
         *
         * @param step The step whose configuration to copy.
         */
        public constructor(step: IdempotencyKeyStep) {
            this.header = step.header
            this.methods = step.methods
            this.keyStrategy = step.keyStrategy
            this.respectExisting = step.respectExisting
        }

        /**
         * Sets the header name to inject. The value is stored verbatim but [Request]'s
         * underlying [org.dexpace.sdk.core.http.common.Headers] map is case-insensitive, so
         * `idempotency-key` and `Idempotency-Key` interoperate.
         *
         * @param header Header name (non-blank).
         * @return This builder.
         */
        public fun header(header: String): Builder =
            apply {
                this.header = header
            }

        /**
         * Sets the HTTP methods that trigger header injection. The provided set is copied
         * into an immutable snapshot so later mutations to the caller's set do not leak in.
         *
         * @param methods Methods that should receive the header.
         * @return This builder.
         */
        public fun methods(methods: Set<Method>): Builder =
            apply {
                this.methods = freezeMethods(methods)
            }

        /**
         * Sets the key-generation strategy using a Kotlin function. Invoked at most once per
         * applicable request, only after the method/header checks decide injection is needed.
         *
         * @param strategy Function returning the next key.
         * @return This builder.
         */
        public fun keyStrategy(strategy: () -> String): Builder =
            apply {
                this.keyStrategy = strategy
            }

        /**
         * Sets the key-generation strategy from a Java [Supplier]. Internally adapted to the
         * Kotlin function shape, so the step's behaviour is identical regardless of which
         * overload was used.
         *
         * @param supplier Supplier producing the next key.
         * @return This builder.
         */
        public fun keyStrategy(supplier: Supplier<String>): Builder =
            apply {
                this.keyStrategy = { supplier.get() }
            }

        /**
         * Sets whether an existing header on the request is respected. See class KDoc for
         * the semantics of `true` vs `false`.
         *
         * @param respectExisting `true` to preserve an existing header; `false` to overwrite.
         * @return This builder.
         */
        public fun respectExisting(respectExisting: Boolean): Builder =
            apply {
                this.respectExisting = respectExisting
            }

        /**
         * Builds the immutable [IdempotencyKeyStep].
         *
         * @return The configured step.
         */
        override fun build(): IdempotencyKeyStep =
            IdempotencyKeyStep(
                header = header,
                methods = methods,
                keyStrategy = keyStrategy,
                respectExisting = respectExisting,
            )
    }

    public companion object {
        /** Default header name — the de-facto standard popularised by Stripe. */
        public const val DEFAULT_HEADER: String = "Idempotency-Key"

        /**
         * Default method set: `POST`, `PUT`, `PATCH`. These are the HTTP methods whose
         * server-side effects are not idempotent by spec, so a key on retry is meaningful.
         */
        public val DEFAULT_METHODS: Set<Method> = freezeMethods(setOf(Method.POST, Method.PUT, Method.PATCH))

        /** Default strategy: a fresh random [UUID] per request, rendered as a string. */
        public val DEFAULT_KEY_STRATEGY: () -> String = { UUID.randomUUID().toString() }

        /** Default respect-existing behaviour: caller-set headers always win. */
        public const val DEFAULT_RESPECT_EXISTING: Boolean = true

        /**
         * Java-friendly factory matching the SDK-wide `builder()` idiom. Equivalent to
         * `new IdempotencyKeyStep.Builder()`.
         */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Convenience factory: returns a step using every documented default.
         */
        @JvmStatic
        public fun default(): IdempotencyKeyStep = Builder().build()

        /**
         * Returns an immutable snapshot of [methods]. Empty sets fall back to an empty
         * unmodifiable view (avoids materialising a zero-size [EnumSet]).
         */
        private fun freezeMethods(methods: Set<Method>): Set<Method> =
            if (methods.isEmpty()) {
                emptySet()
            } else {
                Collections.unmodifiableSet(EnumSet.copyOf(methods))
            }
    }
}
