package org.dexpace.sdk.core.context

import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext

interface RequestContext {
    val instrumentationContext: InstrumentationContext
        get() = NoopInstrumentationContext
}