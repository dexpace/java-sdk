/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation.metrics

/**
 * A no-operation [Meter] that discards every measurement.
 *
 * `NoopMeter` is the default meter for any pipeline step that accepts a meter but has
 * not been configured with a real implementation. It returns shared [NoopCounter] and
 * [NoopHistogram] singletons; instrument creation and measurement emission both allocate
 * nothing and the JIT inlines the empty method bodies away on the hot path.
 *
 * Consuming applications wire up real metric emission by installing a concrete `Meter`
 * (e.g. from a future `sdk-instrumentation-otel` adapter module). `sdk-core` itself
 * deliberately ships no metrics runtime.
 */
public object NoopMeter : Meter {
    /**
     * Returns the shared [NoopCounter] singleton, ignoring all arguments.
     */
    override fun counter(
        name: String,
        description: String,
        unit: String,
    ): LongCounter = NoopCounter

    /**
     * Returns the shared [NoopHistogram] singleton, ignoring all arguments.
     */
    override fun histogram(
        name: String,
        description: String,
        unit: String,
    ): DoubleHistogram = NoopHistogram
}

/**
 * Internal singleton counter that discards every increment.
 *
 * Only [NoopMeter.counter] exposes this instance; it is not part of the public API so
 * future implementations are free to change strategy (e.g. per-call allocation, lazy
 * instrument lookup) without breaking callers.
 */
internal object NoopCounter : LongCounter {
    override fun add(
        value: Long,
        attributes: Map<String, Any>,
    ) {
        // No-op. JIT inlines this to nothing at the call site.
    }
}

/**
 * Internal singleton histogram that discards every recorded value.
 *
 * Only [NoopMeter.histogram] exposes this instance; it is not part of the public API so
 * future implementations are free to change strategy without breaking callers.
 */
internal object NoopHistogram : DoubleHistogram {
    override fun record(
        value: Double,
        attributes: Map<String, Any>,
    ) {
        // No-op. JIT inlines this to nothing at the call site.
    }
}
