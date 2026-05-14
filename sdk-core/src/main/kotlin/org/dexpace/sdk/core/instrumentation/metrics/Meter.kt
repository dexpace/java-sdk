package org.dexpace.sdk.core.instrumentation.metrics

/**
 * Factory for metric instruments (counters, histograms).
 *
 * A `Meter` is the entry point for metric emission. Consuming applications install a
 * concrete `Meter` (e.g. an OpenTelemetry-backed implementation from a separate adapter
 * module such as `sdk-instrumentation-otel`); `sdk-core` ships only [NoopMeter] and never
 * pulls a metrics runtime into its dependency graph.
 *
 * ### Naming and units
 *
 * Instrument names, descriptions, and units follow the OpenTelemetry semantic
 * conventions. Names are stable identifiers (e.g. `"http.client.request.duration"`),
 * descriptions are short human-readable strings, and units are UCUM symbols
 * (e.g. `"ms"`, `"By"`, `"{request}"`). `sdk-core` does not validate these values —
 * the underlying meter implementation may reject malformed inputs.
 *
 * ### Thread safety
 *
 * Implementations must be safe to call from multiple threads concurrently. Instrument
 * creation may be invoked once during pipeline construction or on every request; both
 * patterns must be supported without external synchronization.
 *
 * ### Instrument caching
 *
 * Implementations are encouraged (but not required) to return the same instrument
 * instance for repeated calls with identical `(name, description, unit)` triples. The
 * noop implementation does so trivially by returning shared singletons.
 */
public interface Meter {

    /**
     * Returns a [LongCounter] for emitting monotonically increasing integer measurements.
     *
     * @param name the instrument name, following OpenTelemetry semantic conventions.
     * @param description a short human-readable description of what the counter measures.
     * @param unit the unit of measurement as a UCUM symbol (e.g. `"{request}"`, `"By"`).
     * @return a counter instrument. Implementations may cache and return the same instance
     *         for repeated calls with identical arguments.
     */
    public fun counter(name: String, description: String, unit: String): LongCounter

    /**
     * Returns a [DoubleHistogram] for recording distributions of floating-point measurements.
     *
     * @param name the instrument name, following OpenTelemetry semantic conventions.
     * @param description a short human-readable description of what the histogram measures.
     * @param unit the unit of measurement as a UCUM symbol (e.g. `"ms"`, `"s"`).
     * @return a histogram instrument. Implementations may cache and return the same instance
     *         for repeated calls with identical arguments.
     */
    public fun histogram(name: String, description: String, unit: String): DoubleHistogram
}
