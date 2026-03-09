package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.InstrumentationContext

interface CallContext : AutoCloseable {
    val instrumentationContext: InstrumentationContext

    override fun close() {
        ContextStore.remove(instrumentationContext.traceId.value)
    }
}
