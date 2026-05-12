package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.InstrumentationContext

/**
 * Second link in the context promotion chain. Adds the outgoing [Request] to the dispatch
 * context's [InstrumentationContext]; once a [Response] arrives [toExchangeContext]
 * promotes this into an [ExchangeContext].
 */
data class RequestContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
) : CallContext {
    /**
     * Promotes this request context into an [ExchangeContext] bound to [response] and
     * stores the new context in [ContextStore] keyed by the trace id.
     */
    fun toExchangeContext(response: Response): ExchangeContext =
        ExchangeContext(
            instrumentationContext = instrumentationContext,
            request = request,
            response = response,
        ).also {
            ContextStore.set(it.instrumentationContext.traceId.value, it)
        }
}
