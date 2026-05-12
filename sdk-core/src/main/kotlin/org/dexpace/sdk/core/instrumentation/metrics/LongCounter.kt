package org.dexpace.sdk.core.instrumentation.metrics

/**
 * A monotonically increasing 64-bit integer counter.
 *
 * Counters are used to record cumulative measurements that only ever increase — request
 * counts, byte totals, error tallies. The `Long` primitive in the method signature avoids
 * boxing on the hot path.
 *
 * ### Negative values
 *
 * Per the OpenTelemetry specification a counter must only be incremented by non-negative
 * values; calling [add] with a negative value is **undefined behavior** and is the
 * caller's responsibility to avoid. `sdk-core` does not validate the argument at runtime
 * to keep the call path allocation-free; concrete adapter implementations may choose to
 * log a warning and discard negative deltas.
 *
 * ### Thread safety
 *
 * Implementations must be safe to call from multiple threads concurrently. Adding to a
 * counter is expected to be a hot-path operation invoked from request handling threads.
 */
interface LongCounter {

    /**
     * Adds [value] to the counter, optionally tagged with the supplied [attributes].
     *
     * Pass the shared `emptyMap()` (the default) when no attributes are needed to avoid
     * allocating a fresh map per call.
     *
     * @param value the increment to add. Must be non-negative — negative values are
     *              undefined per the OpenTelemetry contract.
     * @param attributes key-value pairs describing the measurement dimensions (e.g.
     *                   `{"http.status_code": 200}`). The map should not be mutated after
     *                   the call; implementations may retain a reference for asynchronous
     *                   export.
     *
     * Note: `@JvmOverloads` is not supported on interface methods in Kotlin, so Java
     * callers must always pass the attributes argument explicitly — use
     * `Collections.emptyMap()` when no attributes are needed.
     */
    fun add(value: Long, attributes: Map<String, Any> = emptyMap())
}
