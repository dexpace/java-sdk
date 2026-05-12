package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.core.util.Clock
import org.dexpace.sdk.core.util.DateTimeRfc1123
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class SetDateStepTest {

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun tearDown() {
        // FixedClock is per-test instance; no globals to reset.
    }

    @Test
    fun `stage is POST_RETRY`() {
        val step = SetDateStep()
        assertEquals(Stage.POST_RETRY, step.stage)
    }

    @Test
    fun `adds Date header on a request that has none`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val clock = FixedClock(instant)
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(clock))
            .build()

        pipeline.send(getRequest("https://api.example.com/x"))

        val sent = fake.requests.single()
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", sent.headers.get(HttpHeaderName.DATE))
    }

    @Test
    fun `overwrites existing Date header`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val clock = FixedClock(instant)
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(clock))
            .build()

        val stale = Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .addHeader("Date", "Wed, 01 Jan 1990 00:00:00 GMT")
            .build()

        pipeline.send(stale)

        val sent = fake.requests.single()
        val values = sent.headers.values(HttpHeaderName.DATE)
        assertEquals(1, values.size, "expected exactly one Date header after overwrite")
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", values.single())
    }

    @Test
    fun `uses RFC 1123 format`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val clock = FixedClock(instant)
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(clock))
            .build()

        pipeline.send(getRequest("https://api.example.com/x"))

        val sent = fake.requests.single()
        val raw = sent.headers.get(HttpHeaderName.DATE) ?: fail("Date header missing")
        // Verify the emitted form round-trips through the RFC 1123 parser to the same instant.
        assertEquals(instant, DateTimeRfc1123.parse(raw))
    }

    @Test
    fun `different Clock instants produce different Date values`() {
        val firstInstant = Instant.parse("2024-01-01T00:00:00Z")
        val secondInstant = Instant.parse("2024-06-15T12:30:45Z")

        val firstFake = FakeHttpClient().enqueue { status(200) }
        HttpPipelineBuilder(firstFake)
            .append(SetDateStep(FixedClock(firstInstant)))
            .build()
            .send(getRequest("https://api.example.com/x"))

        val secondFake = FakeHttpClient().enqueue { status(200) }
        HttpPipelineBuilder(secondFake)
            .append(SetDateStep(FixedClock(secondInstant)))
            .build()
            .send(getRequest("https://api.example.com/x"))

        val firstDate = firstFake.requests.single().headers.get(HttpHeaderName.DATE)
        val secondDate = secondFake.requests.single().headers.get(HttpHeaderName.DATE)
        assertNotNull(firstDate)
        assertNotNull(secondDate)
        assertNotEquals(firstDate, secondDate)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", firstDate)
        assertEquals("Sat, 15 Jun 2024 12:30:45 GMT", secondDate)
    }

    @Test
    fun `integration with FakeHttpClient sees the Date header on the wire`() {
        val instant = Instant.parse("2024-03-10T08:15:30Z")
        val clock = FixedClock(instant)
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(clock))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/resource"))
        assertEquals(200, response.status.code)
        assertEquals(1, fake.callCount)

        val sent = fake.requests.single()
        assertEquals("Sun, 10 Mar 2024 08:15:30 GMT", sent.headers.get(HttpHeaderName.DATE))
        // The header name on the wire should preserve canonical casing.
        // Headers is case-insensitive, but lookups by raw string in canonical form must match.
        assertEquals("Sun, 10 Mar 2024 08:15:30 GMT", sent.headers.get("Date"))
    }

    @Test
    fun `default constructor uses Clock_SYSTEM`() {
        // Round-trip the emitted value through the RFC 1123 parser to verify it's at least
        // a valid header. We can't lock down the exact value without a clock, but we can
        // assert it parses and lives near "now".
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep())
            .build()

        val before = Instant.now()
        pipeline.send(getRequest("https://api.example.com/x"))
        val after = Instant.now()

        val raw = fake.requests.single().headers.get(HttpHeaderName.DATE) ?: fail("Date missing")
        val parsed = DateTimeRfc1123.parse(raw)
        // RFC 1123 has second-level resolution; allow one second of slack on either side.
        assertEquals(true, !parsed.isBefore(before.minusSeconds(1)), "Date precedes test start")
        assertEquals(true, !parsed.isAfter(after.plusSeconds(1)), "Date follows test end")
    }

    @Test
    fun `fallback formatter path is entered when primary formatter rejects the Instant`() {
        // DateTimeRfc1123.format(instant) internally does `instant.atOffset(ZoneOffset.UTC)`,
        // which throws DateTimeException for Instant.MIN (the offset conversion overflows
        // EpochDay). That exception propagates out of DateTimeRfc1123.format, so the step
        // catches it and reaches the fallback formatter. The fallback also rejects Instant.MIN
        // — but the catch branch is exercised before the fallback runs, which is what we need
        // for coverage. The test verifies the call ultimately throws (fallback couldn't format)
        // rather than masquerading the value as success.
        val fake = FakeHttpClient().enqueue { status(200) }
        val fringeClock = object : Clock {
            override fun now(): Instant = Instant.MIN
            override fun monotonic(): Long = 0L
            override fun sleep(duration: Duration) { /* no-op */ }
        }
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(fringeClock))
            .build()

        // Both EMITTER (via atOffset) and FALLBACK reject Instant.MIN — the step throws after
        // entering the catch block. Coverage records the line as visited.
        assertFails {
            pipeline.send(getRequest("https://api.example.com/x"))
        }
    }

    @Test
    fun `substituted request flows downstream rather than the original`() {
        // POST_RETRY runs inside the retry loop. The step substitutes the request via
        // next.process(stamped); verify that downstream steps see the stamped version, not
        // the original. Insert SetDateStep, then a downstream non-pillar step that captures
        // what it received.
        val instant = Instant.parse("2024-02-29T11:22:33Z")
        val clock = FixedClock(instant)
        val fake = FakeHttpClient().enqueue { status(200) }
        val capture = CapturingStep(Stage.PRE_SEND)
        val pipeline = HttpPipelineBuilder(fake)
            .append(SetDateStep(clock))
            .append(capture)
            .build()

        val original = getRequest("https://api.example.com/x")
        pipeline.send(original)

        val seen = capture.lastRequest ?: fail("downstream step never saw a request")
        assertNotNull(seen.headers.get(HttpHeaderName.DATE), "Date header missing downstream")
        assertEquals("Thu, 29 Feb 2024 11:22:33 GMT", seen.headers.get(HttpHeaderName.DATE))
        // FakeHttpClient sees the same substituted request.
        assertEquals(seen.headers.get(HttpHeaderName.DATE), fake.requests.single().headers.get(HttpHeaderName.DATE))
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun getRequest(url: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(url)
            .build()

    /**
     * Test step that captures the request it receives and passes it through unchanged.
     * Used to assert what downstream steps observe after a substituting step like
     * SetDateStep.
     */
    private class CapturingStep(override val stage: Stage) : org.dexpace.sdk.core.http.pipeline.HttpStep {
        @Volatile
        var lastRequest: Request? = null

        override fun process(
            request: Request,
            next: org.dexpace.sdk.core.http.pipeline.PipelineNext,
        ): org.dexpace.sdk.core.http.response.Response {
            lastRequest = request
            return next.process()
        }
    }
}
