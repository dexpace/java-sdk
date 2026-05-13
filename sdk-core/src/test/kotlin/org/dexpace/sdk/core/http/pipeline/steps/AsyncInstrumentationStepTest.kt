package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.FakeSlf4jLogger
import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext
import org.dexpace.sdk.core.instrumentation.NoopTracer
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.SpanId
import org.dexpace.sdk.core.instrumentation.TraceFlags
import org.dexpace.sdk.core.instrumentation.TraceId
import org.dexpace.sdk.core.instrumentation.TraceIdType
import org.dexpace.sdk.core.instrumentation.TraceState
import org.dexpace.sdk.core.instrumentation.Tracer
import org.dexpace.sdk.core.instrumentation.TracingScope
import org.dexpace.sdk.core.instrumentation.installBasicMdcAdapter
import org.dexpace.sdk.core.util.Futures
import org.slf4j.MDC
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.instrumentation.metrics.Meter
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AsyncInstrumentationStepTest {

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `stage is LOGGING`() {
        val step = DefaultAsyncInstrumentationStep()
        assertEquals(Stage.LOGGING, step.stage)
    }

    @Test
    fun `emits http_request and http_response events for a basic GET`() {
        val fakeSlf4j = FakeSlf4jLogger("test.async.instrumentation")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 200))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS),
                    logger = clientLogger,
                ),
            )
            .build()

        val response = pipeline.sendAsync(getRequest("https://api.example.com/items")).join()
        assertEquals(200, response.status.code)

        val events = fakeSlf4j.records.map { rec ->
            rec.keyValues.firstOrNull { it.key == "event" }?.value as? String
        }
        assertTrue(events.contains("http.request"), "expected http.request event to be emitted")
        assertTrue(events.contains("http.response"), "expected http.response event to be emitted")

        // Verify http.response event has the expected fields.
        val responseRecord = fakeSlf4j.records.first { rec ->
            rec.keyValues.any { it.key == "event" && it.value == "http.response" }
        }
        val kv = responseRecord.keyValues.associate { it.key to it.value }
        assertEquals("GET", kv["http.request.method"])
        assertEquals(200L, kv["http.response.status_code"])
        assertNotNull(kv["http.response.duration_ms"])
    }

    @Test
    fun `wraps response body in LoggableResponseBody when BODY_AND_HEADERS level`() {
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(
                okResponseWithBody(request, 200, "hello async body"),
            )
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS),
                ),
            )
            .build()

        val response = pipeline.sendAsync(getRequest("https://api.example.com/data")).join()
        assertEquals(200, response.status.code)
        val body = response.body ?: fail("expected non-null body")
        assertTrue(body is LoggableResponseBody, "response body should be wrapped in LoggableResponseBody")
        // snapshot returns the cached bytes from the eager drain.
        assertEquals("hello async body", body.snapshot().toString(Charsets.UTF_8))
        response.close()
    }

    @Test
    fun `emits failure event when downstream future completes exceptionally`() {
        val fakeSlf4j = FakeSlf4jLogger("test.async.instrumentation.failure")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)
        val tracer = RecordingTracer()
        val fakeAsync = AsyncHttpClient { _ ->
            CompletableFuture<Response>().apply { completeExceptionally(IOException("net error")) }
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                    logger = clientLogger,
                ),
            )
            .build()

        val future = pipeline.sendAsync(getRequest("https://api.example.com/fail"))
        assertTrue(future.isCompletedExceptionally, "pipeline future must complete exceptionally")

        // join() wraps in CompletionException — unwrap to get original IOException.
        lateinit var caught: Throwable
        try {
            future.join()
            fail("expected future.join() to throw")
        } catch (e: Throwable) {
            caught = e
        }
        val root = unwrapCompletionException(caught)
        assertTrue(root is IOException, "unwrapped cause should be IOException")
        assertEquals("net error", root.message)

        // Failure event emitted.
        val failureRecord = fakeSlf4j.records.firstOrNull { rec ->
            rec.keyValues.any { it.key == "event" && it.value == "http.response" } &&
                rec.keyValues.any { it.key == "error.type" }
        }
        assertNotNull(failureRecord, "expected http.response failure event")
        val kv = failureRecord.keyValues.associate { it.key to it.value }
        assertEquals("IOException", kv["error.type"])

        // Span ended with the throwable.
        val span = tracer.starts.single()
        assertFalse(span.endedNormally, "span must end with an error on failure")
        assertNotNull(span.endThrowable)
    }

    @Test
    fun `recordMetrics fires counter and histogram on success`() {
        val meter = RecordingMeter()
        val clock = FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 201))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, meter = meter),
                    clock = clock,
                ),
            )
            .build()

        pipeline.sendAsync(getRequest("https://api.example.com/created")).join()

        // Counter assertions.
        val counter = meter.counters.single()
        assertEquals("http.client.request.count", counter.name)
        assertEquals(listOf(1L), counter.records.map { it.value })
        val counterAttrs = counter.records.single().attributes
        assertEquals("GET", counterAttrs["http.request.method"])
        assertEquals(201, counterAttrs["http.response.status_code"])

        // Histogram assertions.
        val hist = meter.histograms.single()
        assertEquals("http.client.request.duration", hist.name)
        assertEquals(1, hist.records.size)
        // FixedClock that does not auto-advance yields 0ms.
        assertEquals(0.0, hist.records.single().value)
    }

    @Test
    fun `NONE log level spans still start and end on success`() {
        val tracer = RecordingTracer()
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 200))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, tracer = tracer),
                ),
            )
            .build()

        val response = pipeline.sendAsync(getRequest("https://api.example.com/x")).join()
        assertEquals(200, response.status.code)
        assertEquals(1, tracer.starts.size)
        assertEquals("http GET", tracer.starts.single().name)
        assertTrue(tracer.starts.single().endedNormally, "span must end normally on success")
    }

    @Test
    fun `metrics on failure tag error_type instead of status_code`() {
        val meter = RecordingMeter()
        val fakeAsync = AsyncHttpClient { _ ->
            CompletableFuture<Response>().apply {
                completeExceptionally(RuntimeException("kaboom"))
            }
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, meter = meter),
                ),
            )
            .build()

        val future = pipeline.sendAsync(getRequest("https://api.example.com/x"))
        assertTrue(future.isCompletedExceptionally)

        val counter = meter.counters.single()
        val attrs = counter.records.single().attributes
        assertEquals("GET", attrs["http.request.method"])
        assertEquals("RuntimeException", attrs["error.type"])
        assertFalse(attrs.containsKey("http.response.status_code"))
    }

    @Test
    fun `null response body is not wrapped when BODY_AND_HEADERS level`() {
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 204))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS),
                ),
            )
            .build()

        val response = pipeline.sendAsync(getRequest("https://api.example.com/x")).join()
        assertNull(response.body, "204 response with no body must not gain a LoggableResponseBody wrapper")
    }

    @Test
    fun `http_response event carries trace_id from MDC set by span scope`() {
        // B5: verify that the response event captured by FakeSlf4jLogger carries trace.id.
        // We set up MDC before the pipeline call so the MdcSnapshot capture inside the
        // use{} block picks up the trace.id installed by the span's makeCurrentWithLoggingContext.
        installBasicMdcAdapter()
        val fakeSlf4j = FakeSlf4jLogger("test.async.mdc")
        // Use a logger with null mdcKeys to fold all MDC entries (including trace.id).
        val clientLogger = ClientLogger.forTesting(fakeSlf4j, mdcKeys = null)
        val tracer = MdcInjectingTracer(traceId = "mdc-trace-99", spanId = "mdc-span-88")
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 200))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(
                        logLevel = HttpLogLevel.HEADERS,
                        tracer = tracer,
                    ),
                    logger = clientLogger,
                ),
            )
            .build()

        pipeline.sendAsync(getRequest("https://api.example.com/mdc-test")).join()

        val responseRecord = fakeSlf4j.records.firstOrNull { rec ->
            rec.keyValues.any { it.key == "event" && it.value == "http.response" }
        }
        assertNotNull(responseRecord, "expected http.response event")
        val kv = responseRecord.keyValues.associate { it.key to it.value }
        assertEquals("mdc-trace-99", kv["trace.id"], "trace.id must be present in the http.response event")
    }

    @Test
    fun `processAsync handles a synchronous throw from next as a failed future`() {
        // B13: a next step whose processAsync throws synchronously must be normalised to a
        // failed future; failure event emitted; span ended with the throwable.
        val fakeSlf4j = FakeSlf4jLogger("test.async.sync.throw")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)
        val tracer = RecordingTracer()
        val throwingNext = AsyncHttpClient { _ ->
            throw IllegalArgumentException("validation")
        }
        val pipeline = AsyncHttpPipelineBuilder(throwingNext)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                    logger = clientLogger,
                ),
            )
            .build()

        val future = pipeline.sendAsync(getRequest("https://api.example.com/throw"))
        assertTrue(future.isCompletedExceptionally, "future must be exceptionally complete")

        lateinit var caught: Throwable
        try {
            future.join()
            fail("expected future.join() to throw")
        } catch (e: Throwable) {
            caught = e
        }
        val root = Futures.unwrap(caught)
        assertTrue(root is IllegalArgumentException, "unwrapped cause must be IllegalArgumentException")
        assertEquals("validation", root.message)

        // Failure event was emitted.
        val failureRecord = fakeSlf4j.records.firstOrNull { rec ->
            rec.keyValues.any { it.key == "event" && it.value == "http.response" } &&
                rec.keyValues.any { it.key == "error.type" }
        }
        assertNotNull(failureRecord, "expected failure event (http.response with error.type)")

        // span.end(t) was called.
        val span = tracer.starts.single()
        assertFalse(span.endedNormally, "span must end with error on synchronous throw")
        assertNotNull(span.endThrowable)
        assertTrue(span.endThrowable is IllegalArgumentException)
    }

    @Test
    fun `emits http_request event for a POST with a body`() {
        val fakeSlf4j = FakeSlf4jLogger("test.async.instrumentation.post")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)
        val fakeAsync = AsyncHttpClient { request ->
            CompletableFuture.completedFuture(okResponse(request, 200))
        }
        val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
            .append(
                DefaultAsyncInstrumentationStep(
                    options = HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS),
                    logger = clientLogger,
                ),
            )
            .build()

        pipeline.sendAsync(postRequest("https://api.example.com/echo", "payload")).join()

        val requestRecord = fakeSlf4j.records.firstOrNull { rec ->
            rec.keyValues.any { it.key == "event" && it.value == "http.request" }
        }
        assertNotNull(requestRecord, "expected http.request event")
        val kv = requestRecord.keyValues.associate { it.key to it.value }
        assertEquals("POST", kv["http.request.method"])
    }

    // -- Helpers ---------------------------------------------------------------------------------

    private fun getRequest(url: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(url)
            .addHeader("User-Agent", "test-suite/1.0")
            .build()

    private fun postRequest(url: String, body: String): Request =
        Request.builder()
            .method(Method.POST)
            .url(url)
            .body(RequestBody.create(body, MediaType.parse("text/plain")))
            .build()

    private fun okResponse(request: Request, code: Int): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.fromCode(code))
            .build()

    private fun okResponseWithBody(request: Request, code: Int, bodyContent: String): Response {
        val fake = FakeHttpClient().enqueue { status(code).body(bodyContent, MediaType.parse("text/plain")) }
        return fake.execute(request)
    }

    private fun unwrapCompletionException(t: Throwable): Throwable {
        var current = t
        while (current is java.util.concurrent.CompletionException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    // -- Test doubles ----------------------------------------------------------------------------

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

    /**
     * A tracer that installs the given trace/span IDs into MDC when [makeCurrent] is called,
     * so the MdcSnapshot captured inside the span scope picks them up.
     */
    private class MdcInjectingSpan(
        val name: String,
        val traceId: String,
        val spanId: String,
    ) : Span {
        var endedNormally = false
        var endThrowable: Throwable? = null

        override val isRecording: Boolean = true
        override val context: InstrumentationContext = object : InstrumentationContext {
            override val traceIdType = TraceIdType.NOOP
            override val traceId = TraceId(this@MdcInjectingSpan.traceId)
            override val spanId = SpanId(this@MdcInjectingSpan.spanId)
            override val traceFlags = TraceFlags.NOOP
            override val traceState = TraceState.NOOP
            override val isValid = true
            override val isRemote = false
            override val span: Span = Span.NOOP
        }
        override fun setAttribute(key: String, value: Any): Span = this
        override fun setError(errorType: String): Span = this
        override fun makeCurrent(): TracingScope {
            MDC.put("trace.id", traceId)
            MDC.put("span.id", spanId)
            return TracingScope {
                MDC.remove("trace.id")
                MDC.remove("span.id")
            }
        }
        override fun end() { endedNormally = true }
        override fun end(throwable: Throwable) { endedNormally = false; endThrowable = throwable }
    }

    private class MdcInjectingTracer(val traceId: String, val spanId: String) : Tracer {
        override fun startSpan(name: String, attributes: Map<String, Any>): Span =
            MdcInjectingSpan(name, traceId, spanId)
    }
}
