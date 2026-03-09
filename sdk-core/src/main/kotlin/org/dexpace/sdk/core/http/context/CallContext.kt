package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.InstrumentationContext

interface CallContext {
    val instrumentationContext: InstrumentationContext
}
