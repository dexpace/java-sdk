package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext

/**
 * Entry point of the context promotion chain. Carries only the [InstrumentationContext];
 * once a [Request] has been built, [toRequestContext] promotes this into a [RequestContext]
 * which adds the request payload, and later a [Response] promotes that into an
 * [ExchangeContext].
 *
 * The chain `DispatchContext` -> [RequestContext] -> [ExchangeContext] mirrors the
 * lifecycle of a call: dispatch decision, outgoing request, completed exchange. Each
 * promotion produces a new immutable instance and re-registers it with [ContextStore]
 * under the same trace id so downstream observers see the latest snapshot.
 */
data class DispatchContext(
    override val instrumentationContext: InstrumentationContext,
) : CallContext {
    /**
     * Promotes this dispatch context into a [RequestContext] bound to [request] and
     * stores the new context in [ContextStore] keyed by the trace id.
     */
    fun toRequestContext(request: Request): RequestContext =
        RequestContext(
            instrumentationContext = instrumentationContext,
            request = request,
        ).also {
            ContextStore.set(it.instrumentationContext.traceId.value, it)
        }

    companion object {
        /** A dispatch context with a no-op instrumentation context; used when tracing is disabled. */
        fun default(): DispatchContext = DispatchContext(NoopInstrumentationContext)
    }
}
