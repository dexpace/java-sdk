package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.io.OkioIoProvider
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RedirectStepTest {

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun tearDown() {
        // No globals to clean up.
    }

    // ----------------- Type-level invariants -----------------

    @Test
    fun `stage is REDIRECT and final`() {
        val step = DefaultRedirectStep()
        assertEquals(Stage.REDIRECT, step.stage)

        // Custom subclass still inherits REDIRECT — final override at the abstract base.
        val custom = object : RedirectStep() {
            override fun process(
                request: Request,
                next: org.dexpace.sdk.core.http.pipeline.PipelineNext,
            ): Response = next.process()
        }
        assertEquals(Stage.REDIRECT, custom.stage)
    }

    // ----------------- Happy-path follow -----------------

    @Test
    fun `follows 301 with Location header on a GET request`() {
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/v2/x") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount)
        assertEquals("https://api.example.com/x", fake.requests[0].url.toString())
        assertEquals("https://api.example.com/v2/x", fake.requests[1].url.toString())
    }

    @Test
    fun `follows 302 on GET`() {
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "https://api.example.com/moved") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/orig"))
        assertEquals(200, response.status.code)
        assertEquals("https://api.example.com/moved", fake.requests[1].url.toString())
    }

    @Test
    fun `follows 307 on POST with replayable body`() {
        val fake = FakeHttpClient()
            .enqueue { status(307).header("Location", "https://api.example.com/v2") }
            .enqueue { status(201) }

        val pipeline = HttpPipelineBuilder(fake)
            // 307/308 reuse the original method, so POST must be in allowedMethods.
            .append(
                DefaultRedirectStep(
                    HttpRedirectOptions(
                        allowedMethods = EnumSet.of(Method.GET, Method.HEAD, Method.POST),
                    ),
                ),
            )
            .build()

        val body = RequestBody.create("payload".toByteArray(Charsets.UTF_8), MediaType.parse("text/plain"))
        val request = Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/v1")
            .body(body)
            .build()

        val response = pipeline.send(request)
        assertEquals(201, response.status.code)
        assertEquals(Method.POST, fake.requests[1].method)
        // Body is preserved.
        assertNotNull(fake.requests[1].body)
        assertEquals(body, fake.requests[1].body)
    }

    @Test
    fun `follows 308 on PUT with replayable body`() {
        val fake = FakeHttpClient()
            .enqueue { status(308).header("Location", "https://api.example.com/new-loc") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(
                DefaultRedirectStep(
                    HttpRedirectOptions(allowedMethods = EnumSet.allOf(Method::class.java)),
                ),
            )
            .build()

        val body = RequestBody.create("doc".toByteArray(Charsets.UTF_8))
        val request = Request.builder()
            .method(Method.PUT)
            .url("https://api.example.com/old-loc")
            .body(body)
            .build()

        val response = pipeline.send(request)
        assertEquals(200, response.status.code)
        assertEquals(Method.PUT, fake.requests[1].method)
        assertNotNull(fake.requests[1].body)
    }

    // ----------------- Default allow-list behavior -----------------

    @Test
    fun `does NOT follow 301 on POST by default`() {
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/v2") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep()) // default: GET, HEAD only
            .build()

        val request = Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/v1")
            .body(RequestBody.create("x".toByteArray()))
            .build()

        val response = pipeline.send(request)
        assertEquals(301, response.status.code)
        assertEquals(1, fake.callCount)
    }

    // ----------------- Security: Authorization stripping -----------------

    @Test
    fun `strips Authorization header on redirect`() {
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "https://other.example.com/v2") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val request = Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .addHeader("Authorization", "Bearer secret-token")
            .build()

        pipeline.send(request)

        val reissued = fake.requests[1]
        assertNull(reissued.headers.get(HttpHeaderName.AUTHORIZATION))
        // Original request, however, still has it (immutable).
        assertEquals("Bearer secret-token", request.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `strips Authorization even on same-origin redirect`() {
        // Defensive: auth re-stamping is the AuthStep's job, not ours.
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "https://api.example.com/v2") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val request = Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/v1")
            .addHeader("Authorization", "Bearer secret-token")
            .build()

        pipeline.send(request)

        val reissued = fake.requests[1]
        assertNull(reissued.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    // ----------------- Loop detection -----------------

    @Test
    fun `loop detection returns last response without infinite loop`() {
        // a -> b -> a   → URI set already contains a → return current redirect to a.
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "https://a.example.com/y") }
            .enqueue { status(302).header("Location", "https://a.example.com/x") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://a.example.com/x"))
        assertEquals(302, response.status.code)
        // Two upstream calls: original + first hop. The second redirect's `Location`
        // re-points at the seed URI, so we bail out and return the loop-causing response.
        assertEquals(2, fake.callCount)
    }

    // ----------------- Max attempts -----------------

    @Test
    fun `max attempts returns the last redirect response without throwing`() {
        // maxAttempts = 2 + 3 redirects in a row → after 2 redirect hops, return the 3rd.
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/2") }
            .enqueue { status(301).header("Location", "https://api.example.com/3") }
            .enqueue { status(301).header("Location", "https://api.example.com/4") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep(HttpRedirectOptions(maxAttempts = 2)))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/1"))
        // After two redirect hops we hit maxAttempts; the returned response is the third
        // (last) 301 — the redirect itself, not its target.
        assertEquals(301, response.status.code)
        assertEquals("https://api.example.com/4", response.headers.get(HttpHeaderName.LOCATION))
        assertEquals(3, fake.callCount)
    }

    // ----------------- Location header edge cases -----------------

    @Test
    fun `missing Location header returns the redirect response unchanged`() {
        val fake = FakeHttpClient().enqueue { status(301) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(301, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `empty Location header returns the current response`() {
        val fake = FakeHttpClient().enqueue { status(302).header("Location", "") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(302, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `relative Location URL is resolved against current request URI`() {
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "/v2/x") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/v1/x"))
        assertEquals(200, response.status.code)
        assertEquals("https://api.example.com/v2/x", fake.requests[1].url.toString())
    }

    @Test
    fun `malformed Location URL returns current response without redirecting`() {
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "::not a url::") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(302, response.status.code)
        assertEquals(1, fake.callCount)
    }

    // ----------------- 303 (See Other) handling -----------------

    @Test
    fun `303 with follow303 false does not follow`() {
        val fake = FakeHttpClient()
            .enqueue { status(303).header("Location", "https://api.example.com/done") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep()) // follow303 = false by default
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/submit"))
        assertEquals(303, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `303 with follow303 true re-issues as GET and drops body`() {
        val fake = FakeHttpClient()
            .enqueue { status(303).header("Location", "https://api.example.com/done") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep(HttpRedirectOptions(follow303 = true)))
            .build()

        val request = Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/submit")
            .addHeader("Content-Type", "application/json")
            .addHeader("Content-Length", "11")
            .body(RequestBody.create("{\"x\":\"y\"}".toByteArray()))
            .build()

        val response = pipeline.send(request)
        assertEquals(200, response.status.code)

        val reissued = fake.requests[1]
        assertEquals(Method.GET, reissued.method)
        assertNull(reissued.body)
        assertNull(reissued.headers.get("Content-Type"))
        assertNull(reissued.headers.get("Content-Length"))
    }

    // ----------------- Custom predicate -----------------

    @Test
    fun `custom shouldRedirect predicate overrides the default`() {
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/v2") }
        // Custom predicate refuses every redirect → the default's "follow on 301/GET" doesn't fire.
        val opts = HttpRedirectOptions(shouldRedirect = { false })
        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep(opts))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/v1"))
        assertEquals(301, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `custom shouldRedirect predicate receives accurate condition`() {
        val seenConditions = mutableListOf<HttpRedirectCondition>()
        val opts = HttpRedirectOptions(
            shouldRedirect = { c ->
                seenConditions.add(c)
                true // delegate to the default behavior of recreating the request
            },
            allowedMethods = EnumSet.of(Method.GET),
        )
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/v2") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep(opts))
            .build()

        pipeline.send(getRequest("https://api.example.com/v1"))

        assertEquals(1, seenConditions.size)
        val cond = seenConditions[0]
        assertEquals(0, cond.tryCount)
        assertTrue(cond.redirectedUris.contains("https://api.example.com/v1"))
    }

    // ----------------- No redirect path -----------------

    @Test
    fun `200 response returns immediately without consulting predicate`() {
        var predicateCalls = 0
        val opts = HttpRedirectOptions(shouldRedirect = {
            predicateCalls++
            true
        })
        val fake = FakeHttpClient().enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep(opts))
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(200, response.status.code)
        assertEquals(0, predicateCalls, "predicate must not be consulted on non-redirect status")
    }

    // ----------------- 307/308 with non-replayable body -----------------

    @Test
    fun `307 with non-replayable body throws IllegalStateException`() {
        val fake = FakeHttpClient()
            .enqueue { status(307).header("Location", "https://api.example.com/v2") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(
                DefaultRedirectStep(
                    HttpRedirectOptions(allowedMethods = EnumSet.allOf(Method::class.java)),
                ),
            )
            .build()

        // Single-use body: a BufferedSource-backed body has isReplayable() = false by default.
        val nonReplayable = object : RequestBody() {
            override fun mediaType() = MediaType.parse("text/plain")
            override fun contentLength(): Long = 5
            override fun isReplayable(): Boolean = false
            override fun writeTo(sink: org.dexpace.sdk.core.io.BufferedSink) {
                sink.write("hello".toByteArray(Charsets.UTF_8))
            }
        }

        val request = Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/v1")
            .body(nonReplayable)
            .build()

        val ex = assertFailsWith<IllegalStateException> {
            pipeline.send(request)
        }
        assertTrue(
            ex.message?.contains("replayable") == true,
            "expected 'replayable' in message but was: ${ex.message}",
        )
    }

    // ----------------- Integration: full pipeline -----------------

    @Test
    fun `full pipeline integration with append and build follows redirect end-to-end`() {
        val fake = FakeHttpClient()
            .enqueue { status(301).header("Location", "https://api.example.com/v2") }
            .enqueue { status(200).body("done", MediaType.parse("text/plain")) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/v1"))
        assertEquals(200, response.status.code)
        assertNotNull(response.body)
        response.close()
    }

    // ----------------- Closes prior redirect response -----------------

    @Test
    fun `prior response is closed before reissuing`() {
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val tracking = TrackingCloseClient(closed)
        val pipeline = HttpPipelineBuilder(tracking)
            .append(DefaultRedirectStep())
            .build()

        pipeline.send(getRequest("https://api.example.com/v1"))

        // At least the first response should have been closed.
        assertTrue(closed.get() >= 1, "expected the redirect response to be closed before reissue")
    }

    // ----------------- Userinfo handling -----------------

    @Test
    fun `userinfo in Location is stripped before reissue`() {
        val fake = FakeHttpClient()
            .enqueue { status(302).header("Location", "https://user:pass@api.example.com/v2") }
            .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        pipeline.send(getRequest("https://api.example.com/v1"))

        val reissued = fake.requests[1]
        assertNull(reissued.url.userInfo, "userinfo must be stripped before reissue")
        assertEquals("api.example.com", reissued.url.host)
    }

    // ----------------- Other non-3xx status codes don't trigger redirect -----------------

    @Test
    fun `404 status does not trigger redirect`() {
        val fake = FakeHttpClient().enqueue { status(404).header("Location", "https://api.example.com/v2") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(404, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `300 multiple-choices is not auto-followed`() {
        val fake = FakeHttpClient().enqueue { status(300).header("Location", "https://api.example.com/v2") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(300, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `304 not-modified is not auto-followed`() {
        val fake = FakeHttpClient().enqueue { status(304).header("Location", "https://api.example.com/v2") }

        val pipeline = HttpPipelineBuilder(fake)
            .append(DefaultRedirectStep())
            .build()

        val response = pipeline.send(getRequest("https://api.example.com/x"))
        assertEquals(304, response.status.code)
        assertEquals(1, fake.callCount)
    }

    // ----------------- Helpers -----------------

    private fun getRequest(url: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(url)
            .build()

    /**
     * Wraps [FakeHttpClient]'s behavior with a counter so we can confirm
     * [Response.close] is called before reissue. Each response's body is replaced
     * with a tracking [org.dexpace.sdk.core.http.response.ResponseBody].
     */
    private class TrackingCloseClient(
        private val closedCounter: java.util.concurrent.atomic.AtomicInteger,
    ) : HttpClient {
        private var calls = 0

        override fun execute(request: Request): Response {
            calls++
            val body = TrackingResponseBody(closedCounter)
            // First call: return a 302; second call: return a 200.
            val statusCode = if (calls == 1) 302 else 200
            val headersBuilder = org.dexpace.sdk.core.http.common.Headers.Builder()
            if (calls == 1) headersBuilder.add("Location", "https://api.example.com/v2")
            return Response.builder()
                .request(request)
                .protocol(org.dexpace.sdk.core.http.common.Protocol.HTTP_1_1)
                .status(org.dexpace.sdk.core.http.response.Status.fromCode(statusCode))
                .headers(headersBuilder.build())
                .body(body)
                .build()
        }
    }

    private class TrackingResponseBody(
        private val closedCounter: java.util.concurrent.atomic.AtomicInteger,
    ) : org.dexpace.sdk.core.http.response.ResponseBody() {
        override fun mediaType(): MediaType? = null
        override fun contentLength(): Long = 0
        override fun source(): org.dexpace.sdk.core.io.BufferedSource =
            fail("not expected to read this body in close-tracking test")

        override fun close() {
            closedCounter.incrementAndGet()
        }
    }
}
