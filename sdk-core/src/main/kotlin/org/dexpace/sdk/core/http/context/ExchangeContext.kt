package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.InstrumentationContext

data class ExchangeContext (
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    val response: Response
): CallContext
