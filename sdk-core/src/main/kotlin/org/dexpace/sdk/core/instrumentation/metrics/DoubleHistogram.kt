package org.dexpace.sdk.core.instrumentation.metrics

/**
 * A histogram that aggregates a distribution of 64-bit floating-point measurements.
 *
 * Histograms record values whose distribution matters — request latencies, payload
 * sizes, queue depths. The `Double` primitive in the method signature avoids boxing on
 * the hot path.
 *
 * ### Non-finite values
 *
 * The OpenTelemetry specification does not define behavior for `Double.NaN`,
 * `Double.POSITIVE_INFINITY`, or `Double.NEGATIVE_INFINITY`. Concrete adapter
 * implementations are expected to filter and log such values rather than propagate
 * corrupted measurements into the export pipeline. `sdk-core`'s noop implementation
 * simply discards every value and therefore tolerates any input without throwing.
 *
 * ### Thread safety
 *
 * Implementations must be safe to call from multiple threads concurrently. Recording a
 * measurement is expected to be a hot-path operation invoked from request handling
 * threads.
 */
interface DoubleHistogram {

    /**
     * Records [value] in the histogram, optionally tagged with the supplied [attributes].
     *
     * Pass the shared `emptyMap()` (the default) when no attributes are needed to avoid
     * allocating a fresh map per call.
     *
     * @param value the measurement to record. Non-finite values (`NaN`, infinities) are
     *              implementation-defined; the noop implementation accepts and discards
     *              them, while production adapters typically filter and warn.
     * @param attributes key-value pairs describing the measurement dimensions (e.g.
     *                   `{"http.request.method": "GET"}`). The map should not be mutated
     *                   after the call; implementations may retain a reference for
     *                   asynchronous export.
     *
     * Note: `@JvmOverloads` is not supported on interface methods in Kotlin, so Java
     * callers must always pass the attributes argument explicitly — use
     * `Collections.emptyMap()` when no attributes are needed.
     */
    fun record(value: Double, attributes: Map<String, Any> = emptyMap())
}
