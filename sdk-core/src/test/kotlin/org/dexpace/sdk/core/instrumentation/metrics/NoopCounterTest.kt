package org.dexpace.sdk.core.instrumentation.metrics

import kotlin.test.Test

class NoopCounterTest {

    @Test
    fun `add accepts zero without throwing`() {
        NoopCounter.add(0L)
        NoopCounter.add(0L, emptyMap())
    }

    @Test
    fun `add accepts positive values without throwing`() {
        NoopCounter.add(1L)
        NoopCounter.add(42L)
        NoopCounter.add(Long.MAX_VALUE)
    }

    @Test
    fun `add accepts negative values without throwing`() {
        // Negative values are undefined per the OpenTelemetry contract, but the noop
        // implementation must remain side-effect-free regardless of input.
        NoopCounter.add(-1L)
        NoopCounter.add(Long.MIN_VALUE)
    }

    @Test
    fun `add accepts an attributes map without throwing`() {
        val attrs = mapOf("http.status_code" to 200, "http.request.method" to "GET")
        NoopCounter.add(1L, attrs)
    }

    @Test
    fun `add tolerates a non-empty attributes map with mixed value types`() {
        // The attributes map is `Map<String, Any>` — values of any type must be accepted.
        val attrs = mapOf(
            "string" to "value",
            "long" to 1L,
            "double" to 3.14,
            "boolean" to true,
        )
        NoopCounter.add(1L, attrs)
    }
}
