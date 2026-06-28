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
 * Terminal link in the context promotion chain. Bundles the [Request] / [Response] pair
 * with the call's [InstrumentationContext] so post-exchange observers (metrics, log
 * sinks, span finalizers) can correlate every artifact of a completed call. Inherits the
 * chain's [callKey] from the [RequestContext] it was promoted from.
 *
 * As the terminal link this is the context whose [close] should be called to evict the
 * chain's [ContextStore] entry. In the normal flow the [callKey] is supplied by
 * [RequestContext.toExchangeContext]. When this context is constructed directly off-chain it
 * defaults to a freshly generated, call-unique key (`traceId:spanId:n`) via
 * [DispatchContext.generateCallKey] — see [DispatchContext] for why the trace/span pair alone is not
 * a collision-safe store key. One consequence of the generated default: two default-constructed
 * instances are not structurally equal, since each generates a distinct key — pin an explicit
 * [callKey] if you need equality.
 *
 * @property operationName Optional schema-defined operation id (e.g. `"GetUser"`) for this call,
 *   or `null` for a raw request with no associated operation. Carried forward from the
 *   [RequestContext] for the tracing seam ([InstrumentationContext.httpTracerFactory]) so
 *   post-exchange span finalizers can label the operation.
 */
public data class ExchangeContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    val response: Response,
    override val callKey: String = DispatchContext.generateCallKey(instrumentationContext),
    val operationName: String? = null,
) : CallContext
