/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.InstrumentationContext

/**
 * Second link in the context promotion chain. Adds the outgoing [Request] to the dispatch
 * context's [InstrumentationContext]; once a [Response] arrives [toExchangeContext]
 * promotes this into an [ExchangeContext]. Inherits the chain's [callKey] from the
 * [DispatchContext] it was promoted from.
 *
 * In the normal flow the [callKey] is supplied by [DispatchContext.toRequestContext] so the
 * whole chain shares one store slot. When this context is constructed directly off-chain it
 * defaults to a freshly minted, call-unique key (`traceId:spanId:n`) via
 * [DispatchContext.mintCallKey] — see [DispatchContext] for why the trace/span pair alone is not
 * a collision-safe store key. One consequence of the minted default: two default-constructed
 * instances are not structurally equal, since each mints a distinct key — pin an explicit
 * [callKey] if you need equality.
 */
public data class RequestContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    override val callKey: String = DispatchContext.mintCallKey(instrumentationContext),
) : CallContext {
    /**
     * Promotes this request context into an [ExchangeContext] bound to [response] and stores
     * the new context in [ContextStore] under this chain's [callKey]. After promotion this
     * request context becomes an intermediate link and must not be closed independently —
     * close the returned [ExchangeContext] instead.
     */
    public fun toExchangeContext(response: Response): ExchangeContext =
        ExchangeContext(
            instrumentationContext = instrumentationContext,
            request = request,
            response = response,
            callKey = callKey,
        ).also {
            ContextStore.set(it.callKey, it)
        }
}
