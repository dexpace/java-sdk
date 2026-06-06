/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

/**
 * Factory for [HttpTracer] instances. The SDK calls [newTracer] once per logical
 * operation; the returned tracer receives every event for that operation's lifecycle
 * before being discarded.
 *
 * Modelled as a `fun interface` so Kotlin callers can supply a lambda and Java callers
 * can supply a method reference or single-method anonymous class. The default
 * implementation, [NoopHttpTracerFactory], returns the shared [NoopHttpTracer] singleton.
 *
 * ## Thread-safety
 *
 * [newTracer] may be invoked concurrently from multiple threads — the SDK does not
 * serialize operation starts. Implementations must be safe to call from any thread.
 */
public fun interface HttpTracerFactory {
    /**
     * Returns the tracer that will receive events for one operation.
     *
     * @param operationName Schema-defined operation id (e.g. `"GetUser"`), or `null` for
     *   raw `HttpClient.execute(...)` calls that have no associated operation name.
     * @param attributes Static attributes attached to every event the returned tracer
     *   emits — typically the service name, endpoint, region, and any caller-supplied
     *   tags. Implementations should treat the map as immutable; callers do not promise
     *   to hold a reference past the [newTracer] call.
     * @return A tracer for this operation. Implementations may return a shared singleton
     *   (the default) or a fresh per-operation instance.
     */
    public fun newTracer(
        operationName: String?,
        attributes: Map<String, String>,
    ): HttpTracer
}

/**
 * Default [HttpTracerFactory] that returns the no-op tracer for every call. Shipped as
 * a Kotlin `object` so the instance is shared process-wide.
 *
 * This is the default factory installed on [InstrumentationContext], so existing call
 * sites pay zero cost for tracing unless they explicitly install a real factory.
 */
public object NoopHttpTracerFactory : HttpTracerFactory {
    override fun newTracer(
        operationName: String?,
        attributes: Map<String, String>,
    ): HttpTracer = NoopHttpTracer
}
