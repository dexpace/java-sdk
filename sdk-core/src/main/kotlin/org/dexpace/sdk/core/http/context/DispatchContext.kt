package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext

/**
 * Represents the execution context of a pipeline.
 * This interface provides access to key components required during the execution of a pipeline,
 * such as the pipeline itself, the associated request and response,
 * and a unique identifier for the current pipeline execution run.
 */

data class DispatchContext(
    override val instrumentationContext: InstrumentationContext,
) : CallContext {
    fun toRequestContext(request: Request) : RequestContext =
        RequestContext(
            instrumentationContext = instrumentationContext,
            request = request
        ).also {
            ContextStore.set(it.instrumentationContext.traceId.value, it)
        }

    companion object {
        fun default() : DispatchContext =
            DispatchContext(
                NoopInstrumentationContext,
            )
    }
}
