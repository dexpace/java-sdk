package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.Stage

/**
 * Pillar step at [Stage.LOGGING]. Wraps the request/response in loggable bodies, emits
 * structured `http.request` / `http.response` events through a [org.dexpace.sdk.core.instrumentation.ClientLogger],
 * applies URL and header redaction, starts a distributed-trace span via the configured
 * [org.dexpace.sdk.core.instrumentation.Tracer], and records request count + latency metrics
 * through the configured [org.dexpace.sdk.core.instrumentation.metrics.Meter].
 *
 * The base is `abstract` because the stage is locked to [Stage.LOGGING] at the type level —
 * users implementing custom instrumentation override [process] but inherit the pillar slot.
 * The shipped concrete implementation is [DefaultInstrumentationStep].
 */
public abstract class InstrumentationStep : HttpStep {
    final override val stage: Stage = Stage.LOGGING
}
