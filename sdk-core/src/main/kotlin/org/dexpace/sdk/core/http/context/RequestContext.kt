package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.instrumentation.InstrumentationContext

data class RequestContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
) : CallContext {
    fun toExchangeContext(response: Response) : ExchangeContext =
        ExchangeContext(
            instrumentationContext = instrumentationContext,
            request = request,
            response = response
        )
}
