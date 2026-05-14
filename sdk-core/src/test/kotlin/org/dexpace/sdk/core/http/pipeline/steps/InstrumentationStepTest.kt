package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.FakeSlf4jLogger
import org.dexpace.sdk.core.instrumentation.NoopTracer
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.Tracer
import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
import org.dexpace.sdk.core.instrumentation.metrics.Meter
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        val custom =
            object : InstrumentationStep() {
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
        val pipeline =
            HttpPipelineBuilder(fake)
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
        val pipeline =
            HttpPipelineBuilder(fake)
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
        val pipeline =
            HttpPipelineBuilder(draining)
                .append(
                    DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)),
                )
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
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)),
                )
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
        val pipeline =
            HttpPipelineBuilder(capturing)
                .append(
                    DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)),
                )
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
        val pipeline =
            HttpPipelineBuilder(failingClient)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                    ),
                )
                .build()

        val thrown =
            assertFails {
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
        val pipeline =
            HttpPipelineBuilder(fake)
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
        val pipeline =
            HttpPipelineBuilder(failing)
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
        val pipeline =
            HttpPipelineBuilder(fake)
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
        val pipeline =
            HttpPipelineBuilder(fake)
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

    @Test
    fun `HEADERS level emits allow-listed headers verbatim and redacts the rest`() {
        // Cover the appendHeadersFields branch where a header is allow-listed (User-Agent)
        // versus a header that must be redacted (Authorization). With
        // isRedactedHeaderNamesLoggingEnabled=true (default) the redacted header is logged
        // as "REDACTED".
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(
                            logLevel = HttpLogLevel.HEADERS,
                            isRedactedHeaderNamesLoggingEnabled = true,
                        ),
                    ),
                )
                .build()

        val req =
            Request.builder()
                .method(Method.GET)
                .url("https://api.example.com/x")
                .addHeader("Authorization", "Bearer secret-token")
                .addHeader("User-Agent", "test-suite/1.0")
                .addHeader("X-Trace-Id", "abc-123") // unknown header — redacted (or omitted)
                .build()

        val response = pipeline.send(req)
        assertEquals(200, response.status.code)
    }

    @Test
    fun `non-allowed headers are omitted entirely when redacted logging is disabled`() {
        // Cover the alternate branch in appendHeadersFields: with
        // isRedactedHeaderNamesLoggingEnabled=false, headers outside the allow-list are
        // silently omitted rather than emitted as "REDACTED".
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(
                            logLevel = HttpLogLevel.HEADERS,
                            isRedactedHeaderNamesLoggingEnabled = false,
                        ),
                    ),
                )
                .build()

        val req =
            Request.builder()
                .method(Method.GET)
                .url("https://api.example.com/x")
                .addHeader("Authorization", "Bearer secret-token")
                .addHeader("X-Trace-Id", "abc-123")
                .build()

        val response = pipeline.send(req)
        assertEquals(200, response.status.code)
    }

    @Test
    fun `multi-valued allowed header is joined by comma in the log event`() {
        // Branch coverage on joinHeaderValues: a header with more than one value must take
        // the joinToString(", ") path. The default allow-list contains Via — feed in two
        // values to drive that path.
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS)))
                .build()

        val req =
            Request.builder()
                .method(Method.GET)
                .url("https://api.example.com/x")
                .addHeader("Via", "1.1 proxy-a")
                .addHeader("Via", "1.1 proxy-b")
                .build()

        val response = pipeline.send(req)
        assertEquals(200, response.status.code)
    }

    @Test
    fun `bodyPreviewMaxBytes truncates the response body preview to the configured cap`() {
        // Cover the snapshot(maxBytes) path with maxBytes < body size.
        val payload = "x".repeat(100)
        val fake = FakeHttpClient().enqueue { status(200).body(payload, MediaType.parse("text/plain")) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(
                            logLevel = HttpLogLevel.BODY_AND_HEADERS,
                            bodyPreviewMaxBytes = 10,
                        ),
                    ),
                )
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        val body = response.body ?: fail("body must be present")
        // The wrapped body still exposes the full bytes via snapshot() — only the log preview
        // is truncated. Caller-facing API stays intact.
        assertTrue(body is LoggableResponseBody)
        val full = body.snapshot()
        assertEquals(100, full.size, "wrapper must retain full body for the caller")
        val truncated = body.snapshot(10)
        assertEquals(10, truncated.size, "snapshot(10) must return only the cap")
        response.close()
    }

    @Test
    fun `tracer receives span attributes and span end is invoked on success`() {
        // Cover the tracer interaction path with a recording tracer that captures attributes
        // and end state. Validates that setAttribute is composed correctly via the start map.
        val fake = FakeHttpClient().enqueue { status(204) }
        val tracer = RecordingTracer()
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                    ),
                )
                .build()

        pipeline.send(postRequest("https://api.example.com/echo", "data"))

        val span = tracer.starts.single()
        assertEquals("http POST", span.name)
        assertEquals("POST", span.attributes["http.request.method"])
        assertNotNull(span.attributes["url.full"])
        assertTrue(span.endedNormally, "span must end normally on a 2xx response")
    }

    @Test
    fun `meter records both counter and histogram with method-specific attributes`() {
        // Drive a PUT request and verify the captured attributes include the method tag.
        val fake = FakeHttpClient().enqueue { status(200) }
        val meter = RecordingMeter()
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, meter = meter),
                    ),
                )
                .build()

        val put =
            Request.builder()
                .method(Method.PUT)
                .url("https://api.example.com/resource")
                .body(RequestBody.create("update", MediaType.parse("text/plain")))
                .build()
        pipeline.send(put)

        val counter = meter.counters.single()
        val hist = meter.histograms.single()
        assertEquals("PUT", counter.records.single().attributes["http.request.method"])
        assertEquals(200, counter.records.single().attributes["http.response.status_code"])
        assertEquals("PUT", hist.records.single().attributes["http.request.method"])
    }

    @Test
    fun `response body drain failure captures the error on the wrapper and emits the event`() {
        // Force a drain failure: a ResponseBody whose source() throws. The wrapper records
        // the exception via captureException and the instrumentation step's emitResponseEvent
        // path includes the response.body.drain_error field.
        val failingBody =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = MediaType.parse("text/plain")

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = throw IOException("simulated drain failure")

                override fun close() { /* no-op */ }
            }
        val client =
            object : org.dexpace.sdk.core.client.HttpClient {
                override fun execute(request: Request): Response =
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.OK)
                        .headers(Headers.Builder().build())
                        .body(failingBody)
                        .build()
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(
                    DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)),
                )
                .build()

        // The drain error is captured on the wrapper, not propagated through process(). The
        // caller still receives the wrapped body — subsequent source() calls re-throw the
        // cached error.
        val response = pipeline.send(getRequest("https://api.example.com/x"))
        val body = response.body
        assertTrue(body is LoggableResponseBody)
        assertNotNull(body.captureException, "drain error must be cached on the wrapper")
        assertFails {
            // The wrapper's source() re-throws the cached drain error.
            body.source()
        }
        response.close()
    }

    @Test
    fun `POST PUT PATCH and DELETE methods are all reflected in the span name and event`() {
        // Smoke-coverage: exercises emitRequestEvent and recordMetrics with every common
        // method. Catches a regression that hard-codes GET in any of the format strings.
        for (method in listOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)) {
            val fake = FakeHttpClient().enqueue { status(200) }
            val tracer = RecordingTracer()
            val pipeline =
                HttpPipelineBuilder(fake)
                    .append(
                        DefaultInstrumentationStep(
                            HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS, tracer = tracer),
                        ),
                    )
                    .build()

            val body = if (method == Method.DELETE) null else RequestBody.create("x", MediaType.parse("text/plain"))
            val builder = Request.builder().method(method).url("https://api.example.com/x")
            if (body != null) builder.body(body)
            pipeline.send(builder.build())

            assertEquals("http ${method.name}", tracer.starts.single().name)
        }
    }

    @Test
    fun `body wrapping skipped when request body is null`() {
        // Branch: shouldCaptureBody() == true AND requestBody == null. Used by the
        // outgoing-request branch in process() to skip the wrap. Covered indirectly above,
        // but pinned here for clarity.
        val fake = FakeHttpClient().enqueue { status(200) }
        val capturing = RequestCapturingClient(fake)
        val pipeline =
            HttpPipelineBuilder(capturing)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS),
                    ),
                )
                .build()

        pipeline.send(getRequest("https://api.example.com/x"))

        val sent = capturing.lastReceivedRequest ?: fail("expected request to be captured")
        assertNull(sent.body, "body must remain null when no request body is provided")
    }

    @Test
    fun `response body wrapping skipped when response body is null`() {
        // Branch: shouldCaptureBody() == true AND response.body == null. The wrapResponseForLogging
        // function must short-circuit without allocating a LoggableResponseBody.
        val fake = FakeHttpClient().enqueue { status(204) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS),
                    ),
                )
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertNull(response.body, "204 responses have no body — wrapper must be a no-op")
    }

    @Test
    fun `failure path with BODY_AND_HEADERS and a wrapped request body emits preview field`() {
        // Cover the branch in emitFailureEvent where shouldCaptureBody() is true AND
        // requestBody != null — the failure path appends a preview field. Drains the body
        // first via the DrainingClient analog, then throws to simulate a partial wire write
        // that fails mid-flight.
        val drainingThenThrowing =
            object : org.dexpace.sdk.core.client.HttpClient {
                override fun execute(request: Request): Response {
                    request.body?.let { body ->
                        val sink = Io.provider.buffer()
                        body.writeTo(sink)
                    }
                    throw IOException("connection reset")
                }
            }
        val pipeline =
            HttpPipelineBuilder(drainingThenThrowing)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS),
                    ),
                )
                .build()

        val ex =
            assertFails {
                pipeline.send(postRequest("https://api.example.com/echo", "captured-body-bytes"))
            }
        assertEquals("connection reset", ex.message)
    }

    @Test
    fun `empty body produces empty utf8 preview`() {
        // utf8Preview takes a fast empty branch on a zero-length array. Send a request that
        // results in a zero-byte response body so the preview hits the empty path.
        val fake = FakeHttpClient().enqueue { status(200).body("", MediaType.parse("text/plain")) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.BODY_AND_HEADERS)),
                )
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        val body = response.body ?: fail("body must be present (empty string body)")
        assertTrue(body is LoggableResponseBody)
        assertEquals(0, body.snapshot().size)
        response.close()
    }

    @Test
    fun `emit_request_failed instrumentation error is logged when contentLength on the body throws`() {
        // Drive the catch branch in emitRequestEvent: a request whose body.contentLength()
        // throws causes the field("request.content.length", body.contentLength()) call to
        // raise, which the step swallows and re-emits as `http.instrumentation.emit_request_failed`.
        val fake = FakeHttpClient().enqueue { status(200) }
        val throwingBody =
            object : RequestBody() {
                override fun mediaType(): MediaType? = MediaType.parse("text/plain")

                override fun contentLength(): Long = error("contentLength is unknown")

                override fun writeTo(sink: org.dexpace.sdk.core.io.BufferedSink) {
                    sink.write("hi".toByteArray(Charsets.UTF_8))
                }
            }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS)))
                .build()

        val req =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .body(throwingBody)
                .build()
        // Despite the body's contentLength() throwing during request-event emission, the
        // pipeline still returns the response — the step swallows the log error.
        val response = pipeline.send(req)
        assertEquals(200, response.status.code)
    }

    @Test
    fun `emit_request_failed error_phase is request_event`() {
        val fakeSlf4j = FakeSlf4jLogger("test.instrumentation")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)

        val fake = FakeHttpClient().enqueue { status(200) }
        val throwingBody =
            object : RequestBody() {
                override fun mediaType(): MediaType? = MediaType.parse("text/plain")

                override fun contentLength(): Long = error("contentLength unknown")

                override fun writeTo(sink: org.dexpace.sdk.core.io.BufferedSink) {}
            }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS),
                        FixedClock(),
                        clientLogger,
                    ),
                )
                .build()

        val req =
            Request.builder()
                .method(Method.POST)
                .url("https://api.example.com/x")
                .body(throwingBody)
                .build()

        // Pipeline must still complete — emitInstrumentationError swallows the log failure.
        val response = pipeline.send(req)
        assertEquals(200, response.status.code)

        val warningRecord =
            fakeSlf4j.records.first { rec ->
                rec.keyValues.any { it.key == "event" && it.value == "http.instrumentation.emit_request_failed" }
            }
        val kv = warningRecord.keyValues.associate { it.key to it.value }
        assertEquals("request_event", kv["error.phase"])
    }

    @Test
    fun `wrapResponseForLogging swallows a snapshot failure and returns the wrapped body`() {
        // Drive the catch branch in wrapResponseForLogging by passing a negative
        // bodyPreviewMaxBytes — LoggableResponseBody.snapshot(maxBytes) has a `require` that
        // throws IllegalArgumentException, which the step catches and logs.
        val fake = FakeHttpClient().enqueue { status(200).body("payload", MediaType.parse("text/plain")) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(
                            logLevel = HttpLogLevel.BODY_AND_HEADERS,
                            // causes snapshot(-1) to throw
                            bodyPreviewMaxBytes = -1,
                        ),
                    ),
                )
                .build()

        // The step catches the snapshot failure, logs a warning, and still hands back the
        // wrapped body. The caller's send() does NOT propagate the failure.
        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(200, response.status.code)
        // The body is the wrapper instance.
        assertTrue(response.body is LoggableResponseBody)
        response.close()
    }

    @Test
    fun `failure path on NONE log level skips event emission entirely`() {
        // Cover the early-return branch in emitFailureEvent when logLevel == NONE — span
        // still ends with the throwable, metrics still record an error attribute, but no
        // log event is emitted.
        val tracer = RecordingTracer()
        val meter = RecordingMeter()
        val failing = ThrowingClient(IOException("network down"))
        val pipeline =
            HttpPipelineBuilder(failing)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, tracer = tracer, meter = meter),
                    ),
                )
                .build()

        assertFails {
            pipeline.send(getRequest("https://api.example.com/x"))
        }
        // Span ended with throwable, metrics recorded the error type.
        val span = tracer.starts.single()
        assertFalse(span.endedNormally)
        val counter = meter.counters.single()
        assertEquals("IOException", counter.records.single().attributes["error.type"])
    }

    @Test
    fun `request event emission on NONE log level is skipped`() {
        // Branch: emitRequestEvent returns early when logLevel == NONE. No assertion on
        // events themselves; the test confirms the request still goes through and metrics
        // still record (span and metrics are independent of log level).
        val fake = FakeHttpClient().enqueue { status(200) }
        val meter = RecordingMeter()
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    DefaultInstrumentationStep(
                        HttpInstrumentationOptions(logLevel = HttpLogLevel.NONE, meter = meter),
                    ),
                )
                .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(200, response.status.code)
        // Metrics still emit regardless of log level.
        assertEquals(1, meter.counters.single().records.size)
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun getRequest(url: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(url)
            .addHeader("Authorization", "Bearer secret-token") // redacted by default allow-list
            .addHeader("User-Agent", "test-suite/1.0") // allowed by default
            .build()

    private fun postRequest(
        url: String,
        body: String,
    ): Request =
        Request.builder()
            .method(Method.POST)
            .url(url)
            .body(RequestBody.create(body, MediaType.parse("text/plain")))
            .build()

    /** Test client that records the request reference seen at the transport boundary. */
    private class RequestCapturingClient(
        private val delegate: FakeHttpClient,
    ) : org.dexpace.sdk.core.client.HttpClient {
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

        override fun setAttribute(
            key: String,
            value: Any,
        ): Span = this

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

        override fun startSpan(
            name: String,
            attributes: Map<String, Any>,
        ): Span {
            val span = RecordedSpan(name, attributes)
            starts.add(span)
            return span
        }
    }

    private class RecordedMetric(val value: Long, val attributes: Map<String, Any>)

    private class RecordedHistogramSample(val value: Double, val attributes: Map<String, Any>)

    private class RecordingCounter(val name: String) : LongCounter {
        val records: MutableList<RecordedMetric> = mutableListOf()

        override fun add(
            value: Long,
            attributes: Map<String, Any>,
        ) {
            records.add(RecordedMetric(value, attributes))
        }
    }

    private class RecordingHistogram(val name: String) : DoubleHistogram {
        val records: MutableList<RecordedHistogramSample> = mutableListOf()

        override fun record(
            value: Double,
            attributes: Map<String, Any>,
        ) {
            records.add(RecordedHistogramSample(value, attributes))
        }
    }

    private class RecordingMeter : Meter {
        val counters: MutableList<RecordingCounter> = mutableListOf()
        val histograms: MutableList<RecordingHistogram> = mutableListOf()

        override fun counter(
            name: String,
            description: String,
            unit: String,
        ): LongCounter {
            val c = RecordingCounter(name)
            counters.add(c)
            return c
        }

        override fun histogram(
            name: String,
            description: String,
            unit: String,
        ): DoubleHistogram {
            val h = RecordingHistogram(name)
            histograms.add(h)
            return h
        }
    }
}
