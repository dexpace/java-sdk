/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Entry point of the context promotion chain. Carries the [InstrumentationContext] and the
 * per-call [callKey]; once a [Request] has been built, [toRequestContext] promotes this into
 * a [RequestContext] which adds the request payload, and later a [Response] promotes that
 * into an [ExchangeContext].
 *
 * The chain `DispatchContext` -> [RequestContext] -> [ExchangeContext] mirrors the
 * lifecycle of a call: dispatch decision, outgoing request, completed exchange. Each
 * promotion produces a new immutable instance and re-registers it with [ContextStore]
 * under the **same** [callKey], so downstream observers see the latest snapshot and
 * concurrent calls — even ones sharing a trace id — never collide (see [CallContext]).
 *
 * The default [callKey] is derived from the trace and span ids (`traceId:spanId`), which is
 * adequate when the span id is genuinely per-call. Untraced calls share constant no-op ids,
 * so [default] mints a process-unique key instead; supply an explicit [callKey] to override.
 */
public data class DispatchContext(
    override val instrumentationContext: InstrumentationContext,
    override val callKey: String = deriveCallKey(instrumentationContext),
) : CallContext {
    /**
     * Promotes this dispatch context into a [RequestContext] bound to [request] and stores
     * the new context in [ContextStore] under this chain's [callKey]. After promotion this
     * dispatch context becomes an intermediate link and must not be closed independently —
     * close the returned [RequestContext] (or its own successor) instead.
     */
    public fun toRequestContext(request: Request): RequestContext =
        RequestContext(
            instrumentationContext = instrumentationContext,
            request = request,
            callKey = callKey,
        ).also {
            ContextStore.set(it.callKey, it)
        }

    public companion object {
        private val mintCounter: AtomicLong = AtomicLong()

        /**
         * Derives the default store key for [instrumentationContext] from its trace and span
         * ids (`traceId:spanId`). Unique per call only when the span id is per-call; the
         * no-op context shares constant ids, so [default] does not use this derivation.
         */
        internal fun deriveCallKey(instrumentationContext: InstrumentationContext): String =
            instrumentationContext.traceId.value + ":" + instrumentationContext.spanId.value

        /**
         * A dispatch context with a no-op instrumentation context; used when tracing is
         * disabled. Mints a process-unique [callKey] because the no-op context's trace and
         * span ids are shared constants — two untraced calls would otherwise collide in
         * [ContextStore].
         */
        public fun default(): DispatchContext =
            DispatchContext(
                instrumentationContext = NoopInstrumentationContext,
                callKey = mintCallKey(NoopInstrumentationContext),
            )

        private fun mintCallKey(instrumentationContext: InstrumentationContext): String =
            deriveCallKey(instrumentationContext) + ":" + mintCounter.incrementAndGet()
    }
}
