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
 * The default [callKey] appends a process-unique counter to the trace/span derivation
 * (`traceId:spanId:n`), so it is call-unique even when the span id is not. The trace and span
 * ids alone are not a safe key: the no-op context shares constant ids across untraced calls,
 * an inbound W3C trace shares a trace id across spans, and a tracer may reuse a span id across
 * sibling calls — all of which would otherwise collide in [ContextStore]. Supply an explicit
 * [callKey] to override (e.g. to re-key onto an existing chain).
 */
public data class DispatchContext(
    override val instrumentationContext: InstrumentationContext,
    override val callKey: String = generateCallKey(instrumentationContext),
) : CallContext {
    /**
     * Promotes this dispatch context into a [RequestContext] bound to [request] and stores
     * the new context in [ContextStore] under this chain's [callKey]. The optional
     * [operationName] (a schema-defined operation id such as `"GetUser"`) is carried onto the
     * [RequestContext] and forwarded down the chain so the tracing seam can label the operation;
     * it does not affect the request or dispatch decision. After promotion this dispatch context
     * becomes an intermediate link and must not be closed independently — close the returned
     * [RequestContext] (or its own successor) instead.
     */
    @JvmOverloads
    public fun toRequestContext(
        request: Request,
        operationName: String? = null,
    ): RequestContext =
        RequestContext(
            instrumentationContext = instrumentationContext,
            request = request,
            callKey = callKey,
            operationName = operationName,
        ).also {
            ContextStore.set(it.callKey, it)
        }

    public companion object {
        private val generateCounter: AtomicLong = AtomicLong()

        /**
         * A dispatch context with a no-op instrumentation context; used when tracing is
         * disabled. The primary constructor's default [callKey] already generates a process-unique
         * key, so this just constructs one with the no-op context.
         */
        public fun default(): DispatchContext = DispatchContext(NoopInstrumentationContext)

        /**
         * Generates a call-unique store key by appending a monotonically increasing, process-unique
         * counter to the `traceId:spanId` derivation of [instrumentationContext], yielding
         * `traceId:spanId:n`. The counter disambiguates calls that would otherwise share a
         * trace/span pair (see the class KDoc for why that pair alone is not call-unique), so
         * distinct calls never collide in [ContextStore].
         *
         * Shared with [RequestContext] and [ExchangeContext], which generate the same call-unique
         * default key when constructed directly off-chain (rather than promoted from a
         * [DispatchContext]), so every link in the chain is collision-safe by default.
         */
        internal fun generateCallKey(instrumentationContext: InstrumentationContext): String {
            val traceSpan = "${instrumentationContext.traceId.value}:${instrumentationContext.spanId.value}"
            return "$traceSpan:${generateCounter.incrementAndGet()}"
        }
    }
}
