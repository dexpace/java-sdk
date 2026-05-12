package org.dexpace.sdk.core.instrumentation.metrics

import kotlin.test.Test
import kotlin.test.assertSame

class NoopMeterTest {

    @Test
    fun `counter returns the same singleton instance on every call`() {
        val a = NoopMeter.counter("http.client.request", "request count", "{request}")
        val b = NoopMeter.counter("http.client.request", "request count", "{request}")
        val c = NoopMeter.counter("totally.different.name", "", "")

        assertSame(a, b, "Repeated counter() calls must return the same instance")
        assertSame(a, c, "NoopMeter ignores arguments and returns the shared NoopCounter")
        assertSame(NoopCounter, a, "Returned counter must be the NoopCounter singleton")
    }

    @Test
    fun `histogram returns the same singleton instance on every call`() {
        val a = NoopMeter.histogram("http.client.request.duration", "latency", "ms")
        val b = NoopMeter.histogram("http.client.request.duration", "latency", "ms")
        val c = NoopMeter.histogram("other.metric", "", "")

        assertSame(a, b, "Repeated histogram() calls must return the same instance")
        assertSame(a, c, "NoopMeter ignores arguments and returns the shared NoopHistogram")
        assertSame(NoopHistogram, a, "Returned histogram must be the NoopHistogram singleton")
    }

    @Test
    fun `NoopMeter is itself a stable singleton`() {
        assertSame(NoopMeter, NoopMeter)
    }
}
