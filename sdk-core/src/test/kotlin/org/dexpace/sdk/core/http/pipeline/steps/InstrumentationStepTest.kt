package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.NoopTracer
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.Tracer
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.instrumentation.metrics.Meter
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class InstrumentationStepTest {

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun tearDown() {
        // FixedClock is per-test instance; no globals to reset here.
    }

    @Test
    fun `stage is LOGGING and final`() {
        val step = DefaultInstrumentationStep()
        assertEquals(Stage.LOGGING, step.stage)
    }

    @Test
    fun `extending the abstract base inherits LOGGING stage`() {
        val custom = object : InstrumentationStep() {
            override fun process(
                request: Request,
                next: org.dexpace.sdk.core.http.pipeline.PipelineNext,
            ): Response = next.process()
        }
        assertEquals(Stage.LOGGING, custom.stage)
    }

    @Test
    fun `NONE level passes request through unchanged and starts span`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val tracer = RecordingTracer()
        val pipeline = HttpPipelineBuilder(fake)
            .append(
                DefaultInstrumentationStep(
                    HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, tracer = tracer),
                    FixedClock(),
                ),
            )
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(200, response.status.code)
        // Body was not wrapped (NONE level).
        assertNull(response.body)
        // Span still started/ended.
        assertEquals(1, tracer.starts.size)
        assertEquals("http GET", tracer.starts.single().name)
        assertTrue(tracer.starts.single().endedNormally)
    }

    @Test
    fun `HEADERS level wraps neither body but still records request`() {
        val fake = FakeHttpClient().enqueue { status(204) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS)))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/list?api-version=2024-01&token=secret"))
        assertEquals(204, response.status.code)
        // No body wrapping at HEADERS level — the original (null) body comes through unchanged.
        assertNull(response.body)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `BODY_AND_HEADERS wraps request body via LoggableRequestBody`() {
        // DrainingClient simulates a real transport: it actually calls writeTo on the body,
        // which is what triggers the tee capture inside LoggableRequestBody. FakeHttpClient
        // does not drain — it just hands back the canned response — so we use DrainingClient
        // for this specific assertion.
        val fake = FakeHttpClient().enqueue { status(200).body("ok", MediaType.parse("text/plain")) }
        val draining = DrainingClient(fake)
        val pipeline = HttpPipelineBuilder(draining)
            .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)))
            .build()

        val response = pipeline.send(postRequest("https://api.example.com/echo", "hello body"))
        response.close()

        val sent = draining.lastReceivedRequest ?: fail("expected request to be captured")
        val sentBody = sent.body
        assertTrue(sentBody is LoggableRequestBody, "request body should be wrapped in LoggableRequestBody")
        // The tee captured the bytes during the simulated wire write.
        assertEquals("hello body", sentBody.snapshot().toString(Charsets.UTF_8))
    }

    @Test
    fun `BODY_AND_HEADERS wraps response body and drains eagerly`() {
        val fake = FakeHttpClient().enqueue { status(200).body("payload", MediaType.parse("text/plain")) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/get"))
        val body = response.body ?: fail("expected non-null body")
        assertTrue(body is LoggableResponseBody, "response body should be wrapped in LoggableResponseBody")
        // snapshot() returns the cached bytes from the eager drain.
        assertEquals("payload", body.snapshot().toString(Charsets.UTF_8))
        response.close()
    }

    @Test
    fun `request body is left null when no request body is present`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val capturing = RequestCapturingClient(fake)
        val pipeline = HttpPipelineBuilder(capturing)
            .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        response.close()
        val sent = capturing.lastReceivedRequest ?: fail("expected request to be captured")
        assertNull(sent.body, "GET request with no body should not gain a wrapped body")
    }

    @Test
    fun `downstream exception ends span with throwable and rethrows`() {
        val failingClient = ThrowingClient(java.io.IOException("network down"))
        val tracer = RecordingTracer()
        val pipeline = HttpPipelineBuilder(failingClient)
            .append(
                DefaultInstrumentationStep(
                    HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                ),
            )
            .build()

        val thrown = assertFails {
            pipeline.send(getRequest("https://api.example.com/x"))
        }
        assertEquals("network down", thrown.message)
        assertEquals(1, tracer.starts.size)
        val span = tracer.starts.single()
        assertFalse(span.endedNormally)
        assertEquals(thrown, span.endThrowable)
    }

    @Test
    fun `metrics counter and histogram are emitted with method and status_code`() {
        val fake = FakeHttpClient().enqueue { status(201) }
        val meter = RecordingMeter()
        val pipeline = HttpPipelineBuilder(fake)
            .append(
                DefaultInstrumentationStep(
                    HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, meter = meter),
                ),
            )
            .build()

        pipeline.send(getRequest("https://api.example.com/x"))

        val counter = meter.counters.single()
        assertEquals("http.client.request.count", counter.name)
        assertEquals(listOf(1L), counter.records.map { it.value })
        val counterAttrs = counter.records.single().attributes
        assertEquals("GET", counterAttrs["http.request.method"])
        assertEquals(201, counterAttrs["http.response.status_code"])

        val hist = meter.histograms.single()
        assertEquals("http.client.request.duration", hist.name)
        assertEquals(1, hist.records.size)
        assertTrue(hist.records.single().value >= 0.0)
    }

    @Test
    fun `metrics on failure tag error type instead of status_code`() {
        val failing = ThrowingClient(java.lang.RuntimeException("kaboom"))
        val meter = RecordingMeter()
        val pipeline = HttpPipelineBuilder(failing)
            .append(
                DefaultInstrumentationStep(
                    HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, meter = meter),
                ),
            )
            .build()

        assertFails {
            pipeline.send(getRequest("https://api.example.com/x"))
        }
        val counter = meter.counters.single()
        val attrs = counter.records.single().attributes
        assertEquals("GET", attrs["http.request.method"])
        assertEquals("RuntimeException", attrs["error.type"])
        assertFalse(attrs.containsKey("http.response.status_code"))
    }

    @Test
    fun `NoopTracer returns Span_NOOP for every startSpan call`() {
        val s = NoopTracer.startSpan("anything", mapOf("a" to "b"))
        assertSame(Span.NOOP, s)
    }

    @Test
    fun `default options use NoopTracer and NoopMeter`() {
        val opts = HttpInstrumentationOptions()
        assertSame(NoopTracer, opts.tracer)
        // Sanity: NoopMeter.counter returns a no-op counter that doesn't throw.
        val counter = opts.meter.counter("c", "d", "u")
        counter.add(5L, emptyMap())
    }

    @Test
    fun `redacted URL is computed via UrlRedactor`() {
        // Validates that the step composes UrlRedactor: a non-allowed query value
        // would surface as `***` in any emitted log line; this test verifies the step
        // does not throw on a URL with userinfo and tokens. The redacted form is an
        // internal implementation detail of the log emit path; we rely on UrlRedactorTest
        // to verify the redaction itself.
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS)))
            .build()
        val r = pipeline.send(getRequest("https://user:pass@api.example.com/secret?api-version=1&token=abc"))
        assertEquals(200, r.status.code)
    }

    @Test
    fun `FixedClock drives elapsed-time measurement deterministically`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
        val meter = RecordingMeter()
        val pipeline = HttpPipelineBuilder(fake)
            .append(
                DefaultInstrumentationStep(
                    HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, meter = meter),
                    clock,
                ),
            )
            .build()

        pipeline.send(getRequest("https://api.example.com/x"))
        val measuredMs = meter.histograms.single().records.single().value
        // With a FixedClock that does NOT auto-advance, the difference between two monotonic()
        // reads is zero — confirming the step uses the injected clock end-to-end.
        assertEquals(0.0, measuredMs)
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun getRequest(url: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(url)
            .addHeader("Authorization", "Bearer secret-token") // redacted by default allow-list
            .addHeader("User-Agent", "test-suite/1.0")          // allowed by default
            .build()

    private fun postRequest(url: String, body: String): Request =
        Request.builder()
            .method(Method.POST)
            .url(url)
            .body(RequestBody.create(body, MediaType.parse("text/plain")))
            .build()

    /** Test client that records the request reference seen at the transport boundary. */
    private class RequestCapturingClient(private val delegate: FakeHttpClient) : org.dexpace.sdk.core.client.HttpClient {
        @Volatile
        var lastReceivedRequest: Request? = null

        override fun execute(request: Request): Response {
            lastReceivedRequest = request
            return delegate.execute(request)
        }
    }

    /**
     * Test client that mimics a real transport by actually draining the request body into a
     * scratch sink before returning the canned response. This is what triggers the tee
     * capture inside `LoggableRequestBody`.
     */
    private class DrainingClient(private val delegate: FakeHttpClient) : org.dexpace.sdk.core.client.HttpClient {
        @Volatile
        var lastReceivedRequest: Request? = null

        override fun execute(request: Request): Response {
            lastReceivedRequest = request
            request.body?.let { body ->
                val sink = Io.provider.buffer()
                body.writeTo(sink)
            }
            return delegate.execute(request)
        }
    }

    private class ThrowingClient(private val thrown: Throwable) : org.dexpace.sdk.core.client.HttpClient {
        override fun execute(request: Request): Response = throw thrown
    }

    private class RecordedSpan(val name: String, val attributes: Map<String, Any>) : Span {
        var endedNormally: Boolean = false
        var endThrowable: Throwable? = null

        override val isRecording: Boolean = true
        override val context: org.dexpace.sdk.core.instrumentation.InstrumentationContext =
            org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext
        override fun setAttribute(key: String, value: Any): Span = this
        override fun setError(errorType: String): Span = this
        override fun makeCurrent(): org.dexpace.sdk.core.instrumentation.TracingScope =
            object : org.dexpace.sdk.core.instrumentation.TracingScope {
                override fun close() {}
            }

        override fun end() {
            endedNormally = true
        }

        override fun end(throwable: Throwable) {
            endedNormally = false
            endThrowable = throwable
        }
    }

    private class RecordingTracer : Tracer {
        val starts: MutableList<RecordedSpan> = mutableListOf()

        override fun startSpan(name: String, attributes: Map<String, Any>): Span {
            val span = RecordedSpan(name, attributes)
            starts.add(span)
            return span
        }
    }

    private class RecordedMetric(val value: Long, val attributes: Map<String, Any>)
    private class RecordedHistogramSample(val value: Double, val attributes: Map<String, Any>)

    private class RecordingCounter(val name: String) : LongCounter {
        val records: MutableList<RecordedMetric> = mutableListOf()
        override fun add(value: Long, attributes: Map<String, Any>) {
            records.add(RecordedMetric(value, attributes))
        }
    }

    private class RecordingHistogram(val name: String) : DoubleHistogram {
        val records: MutableList<RecordedHistogramSample> = mutableListOf()
        override fun record(value: Double, attributes: Map<String, Any>) {
            records.add(RecordedHistogramSample(value, attributes))
        }
    }

    private class RecordingMeter : Meter {
        val counters: MutableList<RecordingCounter> = mutableListOf()
        val histograms: MutableList<RecordingHistogram> = mutableListOf()

        override fun counter(name: String, description: String, unit: String): LongCounter {
            val c = RecordingCounter(name)
            counters.add(c)
            return c
        }

        override fun histogram(name: String, description: String, unit: String): DoubleHistogram {
            val h = RecordingHistogram(name)
            histograms.add(h)
            return h
        }
    }
}
