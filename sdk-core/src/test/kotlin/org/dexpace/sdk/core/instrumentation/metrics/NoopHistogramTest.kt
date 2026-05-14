package org.dexpace.sdk.core.instrumentation.metrics

import kotlin.test.Test

class NoopHistogramTest {
    @Test
    fun `record accepts zero without throwing`() {
        NoopHistogram.record(0.0)
        NoopHistogram.record(0.0, emptyMap())
    }

    @Test
    fun `record accepts positive values without throwing`() {
        NoopHistogram.record(1.0)
        NoopHistogram.record(42.5)
        NoopHistogram.record(Double.MAX_VALUE)
    }

    @Test
    fun `record accepts negative values without throwing`() {
        NoopHistogram.record(-1.0)
        NoopHistogram.record(-Double.MAX_VALUE)
        NoopHistogram.record(Double.MIN_VALUE)
    }

    @Test
    fun `record accepts NaN without throwing`() {
        // Real adapters typically filter NaN; the noop impl must simply discard it.
        NoopHistogram.record(Double.NaN)
        NoopHistogram.record(Double.NaN, mapOf("k" to "v"))
    }

    @Test
    fun `record accepts positive and negative infinity without throwing`() {
        NoopHistogram.record(Double.POSITIVE_INFINITY)
        NoopHistogram.record(Double.NEGATIVE_INFINITY)
        NoopHistogram.record(Double.POSITIVE_INFINITY, mapOf("k" to "v"))
    }

    @Test
    fun `record accepts an attributes map without throwing`() {
        val attrs = mapOf("http.request.method" to "GET", "http.response.status_code" to 200)
        NoopHistogram.record(12.5, attrs)
    }
}
