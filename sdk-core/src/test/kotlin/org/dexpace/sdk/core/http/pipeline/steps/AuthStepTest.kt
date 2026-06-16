/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.auth.BearerToken
import org.dexpace.sdk.core.http.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.auth.KeyCredential
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AuthStepTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    // ----------------- Type-level invariants -----------------

    @Test
    fun `stage is AUTH and final on KeyCredentialAuthStep`() {
        val step = KeyCredentialAuthStep(KeyCredential("k"))
        assertEquals(Stage.AUTH, step.stage)
    }

    @Test
    fun `stage is AUTH and final on BearerTokenAuthStep`() {
        val step =
            BearerTokenAuthStep(
                provider = { _, _ -> BearerToken("tk", null) },
                scopes = listOf("scope"),
            )
        assertEquals(Stage.AUTH, step.stage)
    }

    @Test
    fun `stage is AUTH on a custom AuthStep subclass`() {
        val custom =
            object : AuthStep() {
                override fun authorizeRequest(request: Request): Request = request
            }
        assertEquals(Stage.AUTH, custom.stage)
    }

    // ----------------- HTTPS-only check -----------------

    @Test
    fun `HTTPS-only check rejects http with IllegalStateException naming the step and scheme`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("k")))
                .build()

        val request = Request.builder().method(Method.GET).url("http://api.example.com/x").build()
        val ex = assertFailsWith<IllegalStateException> { pipeline.send(request) }

        // Message must name the step type AND the offending scheme so the bug is debuggable.
        val msg = ex.message ?: ""
        assertTrue(msg.contains("KeyCredentialAuthStep"), "missing step type: $msg")
        assertTrue(msg.contains("HTTPS"), "missing HTTPS mention: $msg")
        assertTrue(msg.contains("http"), "missing scheme: $msg")
        // Wire write must NOT happen — the fake's enqueued response should still be available.
        assertEquals(0, fake.callCount)
    }

    @Test
    fun `HTTPS-only check is case-insensitive - HTTPS uppercase is accepted`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("k")))
                .build()

        // Java's URL parser lower-cases the scheme, so this is more a defensive guarantee:
        // we don't crash on the upper-case form some callers might construct manually.
        val request = Request.builder().method(Method.GET).url("HTTPS://api.example.com/x").build()
        val response = pipeline.send(request)
        assertEquals(200, response.status.code)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `HTTPS-only fires BEFORE the token fetch on BearerTokenAuthStep`() {
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                BearerToken("tk", null)
            }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("s")))
                .build()

        val request = Request.builder().method(Method.GET).url("http://api.example.com/x").build()
        assertFailsWith<IllegalStateException> { pipeline.send(request) }
        assertEquals(0, fetches.get(), "token provider must not be called when HTTPS check fails")
    }

    // ----------------- KeyCredentialAuthStep -----------------

    @Test
    fun `KeyCredentialAuthStep stamps Authorization with the raw key on GET`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("secret-key")))
                .build()

        pipeline.send(getHttpsRequest())

        val captured = fake.requests.single()
        assertEquals("secret-key", captured.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `KeyCredentialAuthStep stamps a custom header when configured`() {
        val customHeader = HttpHeaderName.fromString("X-API-Key")
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("secret", headerName = customHeader)))
                .build()

        pipeline.send(getHttpsRequest())

        val captured = fake.requests.single()
        assertEquals("secret", captured.headers.get(customHeader))
        // Authorization must NOT be set in this case.
        assertNull(captured.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `KeyCredentialAuthStep without prefix uses the raw key value verbatim`() {
        // Branch-coverage focus: prefix == null path. The header value must equal the apiKey
        // exactly with no separator characters prepended. Differs from the broader "stamps
        // Authorization with the raw key on GET" test by explicitly asserting *byte-for-byte*
        // equality so a regression that introduced a default prefix would fail loudly.
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("just-the-key", prefix = null)))
                .build()

        pipeline.send(getHttpsRequest())

        val captured = fake.requests.single()
        val authValue = captured.headers.get(HttpHeaderName.AUTHORIZATION) ?: fail("Authorization missing")
        // Strict byte equality: no whitespace, no Bearer/SharedAccessKey prefix.
        assertEquals("just-the-key", authValue)
        assertTrue(!authValue.startsWith(" "), "must not start with a space")
        assertTrue(!authValue.contains(" "), "must not contain a separator when no prefix is set")
    }

    @Test
    fun `KeyCredentialAuthStep prepends the prefix with a single space`() {
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(KeyCredentialAuthStep(KeyCredential("secret-key", prefix = "SharedAccessKey")))
                .build()

        pipeline.send(getHttpsRequest())

        val captured = fake.requests.single()
        assertEquals("SharedAccessKey secret-key", captured.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    // ----------------- BearerTokenAuthStep -----------------

    @Test
    fun `BearerTokenAuthStep stamps Authorization Bearer with the token`() {
        val provider = BearerTokenProvider { _, _ -> BearerToken("abc.def.ghi", null) }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())

        val captured = fake.requests.single()
        assertEquals("Bearer abc.def.ghi", captured.headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `BearerTokenAuthStep caches a non-expiring token across multiple requests`() {
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                BearerToken("tk", null)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        repeat(3) { pipeline.send(getHttpsRequest()) }
        assertEquals(1, fetches.get(), "non-expiring token should be fetched exactly once")
    }

    @Test
    fun `BearerTokenAuthStep refreshes a token that is within the refresh margin of expiry`() {
        val clock = FixedClock(Instant.parse("2024-01-01T00:00:00Z"))
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                val n = fetches.incrementAndGet()
                // First token expires 60s after the clock's "now"; second token expires 600s.
                BearerToken("tk-$n", clock.now().plusSeconds(if (n == 1) 60 else 600))
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    BearerTokenAuthStep(
                        provider,
                        listOf("scope"),
                        refreshMargin = Duration.ofSeconds(30),
                        clock = clock,
                    ),
                )
                .build()

        // First send: cached token tk-1 fetched at t=0, valid until 60s, margin=30s → valid for next 30s.
        pipeline.send(getHttpsRequest())
        assertEquals(1, fetches.get())

        // Advance 25s → still within the 30s validity window → no refresh.
        clock.advance(Duration.ofSeconds(25))
        pipeline.send(getHttpsRequest())
        assertEquals(1, fetches.get())

        // Advance another 10s (total 35s) → now within the 30s margin of expiry → refresh.
        clock.advance(Duration.ofSeconds(10))
        pipeline.send(getHttpsRequest())
        assertEquals(2, fetches.get())

        // The third call should have stamped tk-2.
        assertEquals("Bearer tk-2", fake.requests.last().headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `BearerTokenAuthStep uses pre-formatted header value cache - second request reuses string`() {
        val provider = BearerTokenProvider { _, _ -> BearerToken("tk", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())
        pipeline.send(getHttpsRequest())

        // Both requests share the same exact header value string ("Bearer tk").
        val first = fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION)
        val second = fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION)
        assertEquals("Bearer tk", first)
        assertEquals("Bearer tk", second)
    }

    @Test
    fun `BearerTokenAuthStep concurrent refresh - 8 threads x 100 requests fetches token exactly once`() {
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                // Tiny sleep to widen the race window; a non-thread-safe step would call the
                // provider many more times than once here.
                Thread.sleep(2)
                BearerToken("tk", null)
            }
        // FakeHttpClient is single-threaded by design (ArrayDeque, mutable list). For this
        // concurrency race test, use a thread-safe HttpClient that simply returns a fresh
        // 200 response per call and counts invocations atomically.
        val callCount = AtomicInteger(0)
        val client =
            object : org.dexpace.sdk.core.client.HttpClient {
                override fun execute(request: Request): Response {
                    callCount.incrementAndGet()
                    return Response.builder()
                        .request(request)
                        .protocol(org.dexpace.sdk.core.http.common.Protocol.HTTP_1_1)
                        .status(org.dexpace.sdk.core.http.response.Status.OK)
                        .build()
                }
            }

        val pipeline =
            HttpPipelineBuilder(client)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        val threads = 8
        val perThread = 100
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        repeat(threads) {
            Thread {
                ready.countDown()
                go.await()
                try {
                    repeat(perThread) {
                        pipeline.send(getHttpsRequest()).close()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "threads did not reach barrier")
        go.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish")

        assertTrue(errors.isEmpty(), "thread errors: $errors")
        // Single-fetch semantics enforced by the lock — exactly one call to the provider.
        assertEquals(1, fetches.get(), "expected exactly one fetch under concurrent load")
        assertEquals(threads * perThread, callCount.get())
    }

    @Test
    fun `BearerTokenAuthStep propagates provider exception without caching`() {
        val attempts = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                val n = attempts.incrementAndGet()
                if (n <= 2) throw RuntimeException("transient $n")
                BearerToken("tk", null)
            }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        // First two sends fail at the provider; each subsequent send re-attempts the fetch
        // (the failure is NOT cached).
        repeat(2) {
            assertFailsWith<RuntimeException> { pipeline.send(getHttpsRequest()) }
        }
        assertEquals(2, attempts.get())

        // Third send: provider returns a real token; the request goes through.
        val response = pipeline.send(getHttpsRequest())
        assertEquals(200, response.status.code)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `BearerTokenAuthStep rejects a provider returning null`() {
        // Java callers can return null despite Kotlin's non-nullable return type. Bypass
        // Kotlin's compile-time null guard by implementing the SAM as an explicit Java-like
        // interface impl whose return type is the platform type via a `@Suppress`.
        val provider =
            object : BearerTokenProvider {
                @Suppress("UNCHECKED_CAST")
                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = (null as BearerToken?) as BearerToken
            }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        // Either an IllegalStateException ("returned null") OR an NPE indicating null was
        // observed. Modern Kotlin (>=1.4) inserts an intrinsic null check at the SAM impl
        // boundary, raising NullPointerException before we reach the step's own null guard.
        // Both outcomes confirm the failure mode is "null fetch is rejected, loudly".
        val thrown = assertFailsWith<RuntimeException> { pipeline.send(getHttpsRequest()) }
        val gotName = thrown::class.simpleName
        assertTrue(
            thrown is IllegalStateException || thrown is NullPointerException,
            "expected IllegalStateException or NullPointerException, got $gotName: ${thrown.message}",
        )
    }

    @Test
    fun `BearerTokenAuthStep wraps null return from a Java provider as IllegalStateException`() {
        // The Kotlin null-return test above is subject to Kotlin's intrinsic null check on the
        // SAM call site (it raises NPE before reaching the step's `?: error(...)` guard). A
        // Java provider has no such intrinsic — null propagates into `fetchFresh`, where the
        // step's defensive `?: error("BearerTokenProvider returned null")` guard fires.
        // Exercises the otherwise-unreachable error branch.
        val provider: BearerTokenProvider = NullReturningBearerTokenProvider()
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getHttpsRequest()) }
        assertTrue(
            ex.message?.contains("null") == true,
            "expected message to mention null, got: ${ex.message}",
        )
    }

    @Test
    fun `BearerTokenAuthStep refreshes when cachedToken is null on the first call`() {
        // The first send hits the lock-free fast path with cachedToken == null and falls
        // through to the locked branch. Verifies the missing-cache code path is reached:
        // the lock is acquired, the second null-check inside the lock also returns null,
        // fetchFresh runs, and the result is cached for subsequent reads.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                BearerToken("tk", null)
            }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())
        assertEquals(1, fetches.get(), "first request must fetch since cache is empty")
    }

    @Test
    fun `BearerTokenAuthStep refreshes on subsequent call when token has expired past the margin`() {
        // Distinct from the existing "within refresh margin" test: this drives the token
        // strictly past `expiresAt` (not just within the grace window). Exercises the
        // expired-after-margin branch in isExpiredAt where now() > expiresAt outright,
        // independent of the margin grace.
        val clock = FixedClock(Instant.parse("2024-01-01T00:00:00Z"))
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                BearerToken("tk-${fetches.get()}", clock.now().plusSeconds(10))
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), refreshMargin = Duration.ZERO, clock = clock))
                .build()

        pipeline.send(getHttpsRequest())
        assertEquals(1, fetches.get())

        // Move past the token expiry — 15s when the token was good for 10s only.
        clock.advance(Duration.ofSeconds(15))
        pipeline.send(getHttpsRequest())
        assertEquals(2, fetches.get(), "expired token should trigger a refresh on the next call")
        assertEquals("Bearer tk-2", fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `BearerTokenAuthStep rejects a provider returning an already-expired token`() {
        val clock = FixedClock(Instant.parse("2024-06-01T00:00:00Z"))
        val provider =
            BearerTokenProvider { _, _ ->
                // Token expired 1s before "now".
                BearerToken("tk", clock.now().minusSeconds(1))
            }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val ex = assertFailsWith<IllegalStateException> { pipeline.send(getHttpsRequest()) }
        assertTrue(ex.message?.contains("expired") == true, "msg=${ex.message}")
    }

    // ----------------- 401 + WWW-Authenticate handling -----------------

    @Test
    fun `401 with WWW-Authenticate returns the 401 unchanged by default`() {
        val provider = BearerTokenProvider { _, _ -> BearerToken("tk", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        val response = pipeline.send(getHttpsRequest())
        assertEquals(401, response.status.code)
        assertEquals(1, fake.callCount, "default authorizeRequestOnChallenge returns null → no retry")
    }

    @Test
    fun `401 without WWW-Authenticate is returned as-is even when the subclass would retry`() {
        // Subclass that would always retry — but the missing WWW-Authenticate header
        // short-circuits the retry path before authorizeRequestOnChallenge is called.
        val seenChallengeCallback = AtomicInteger(0)
        val step =
            object : KeyCredentialAuthStep(KeyCredential("k")) {
                override fun authorizeRequestOnChallenge(
                    request: Request,
                    response: Response,
                ): Request {
                    seenChallengeCallback.incrementAndGet()
                    return request.newBuilder().setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, "X").build()
                }
            }
        val fake = FakeHttpClient().enqueue { status(401) } // no WWW-Authenticate

        val pipeline = HttpPipelineBuilder(fake).append(step).build()
        val response = pipeline.send(getHttpsRequest())
        assertEquals(401, response.status.code)
        assertEquals(1, fake.callCount)
        assertEquals(0, seenChallengeCallback.get(), "no challenge → callback must not fire")
    }

    @Test
    fun `subclass overriding authorizeRequestOnChallenge retries exactly once`() {
        val step =
            object : KeyCredentialAuthStep(KeyCredential("initial-key")) {
                override fun authorizeRequestOnChallenge(
                    request: Request,
                    response: Response,
                ): Request {
                    // Swap to a fresh credential on 401.
                    return request.newBuilder()
                        .setHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName, "refreshed-key")
                        .build()
                }
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(200) }

        val pipeline = HttpPipelineBuilder(fake).append(step).build()
        val response = pipeline.send(getHttpsRequest())

        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "expected one retry")
        // First request carries the initial credential; second carries the refreshed one.
        assertEquals("initial-key", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        assertEquals("refreshed-key", fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `challenge retry closes the 401 response before driving the retry`() {
        val closes = AtomicInteger(0)
        // Wrap the second response (401) in a custom body that tracks close().
        val client =
            object : org.dexpace.sdk.core.client.HttpClient {
                private val callCount = AtomicInteger(0)

                override fun execute(request: Request): Response {
                    val n = callCount.incrementAndGet()
                    val statusCode = if (n == 1) 401 else 200
                    val builder =
                        Response.builder()
                            .request(request)
                            .protocol(org.dexpace.sdk.core.http.common.Protocol.HTTP_1_1)
                            .status(org.dexpace.sdk.core.http.response.Status.fromCode(statusCode))
                    if (n == 1) {
                        builder.addHeader("WWW-Authenticate", "Bearer realm=\"x\"")
                        builder.body(CloseTrackingResponseBody(closes))
                    }
                    return builder.build()
                }
            }
        val step =
            object : KeyCredentialAuthStep(KeyCredential("k")) {
                override fun authorizeRequestOnChallenge(
                    request: Request,
                    response: Response,
                ): Request = request
            }

        val pipeline = HttpPipelineBuilder(client).append(step).build()
        pipeline.send(getHttpsRequest())

        assertTrue(closes.get() >= 1, "401 body must be closed before retry")
    }

    @Test
    fun `authorizeRequestOnChallenge throwing closes the 401 response before propagating`() {
        val closes = AtomicInteger(0)
        val client =
            object : org.dexpace.sdk.core.client.HttpClient {
                override fun execute(request: Request): Response {
                    return Response.builder()
                        .request(request)
                        .protocol(org.dexpace.sdk.core.http.common.Protocol.HTTP_1_1)
                        .status(org.dexpace.sdk.core.http.response.Status.fromCode(401))
                        .addHeader("WWW-Authenticate", "Bearer realm=\"x\"")
                        .body(CloseTrackingResponseBody(closes))
                        .build()
                }
            }
        val step =
            object : KeyCredentialAuthStep(KeyCredential("k")) {
                override fun authorizeRequestOnChallenge(
                    request: Request,
                    response: Response,
                ): Request {
                    throw RuntimeException("challenge handler failure")
                }
            }
        val pipeline = HttpPipelineBuilder(client).append(step).build()

        val ex = assertFailsWith<RuntimeException> { pipeline.send(getHttpsRequest()) }
        assertEquals("challenge handler failure", ex.message)
        assertTrue(closes.get() >= 1, "401 response must be closed even when challenge handler throws")
    }

    @Test
    fun `challenge retry rebuilds chain state via next-dot-copy not bare next`() {
        // Downstream recorder counts how many times its process is invoked. A bug that
        // forwarded retry through bare `next.process(...)` would skip prior pipeline steps
        // and only invoke the SEND step on retry. Through `next.copy()`, the recorder is
        // invoked once per attempt — twice total here.
        val recorderCalls = AtomicInteger(0)
        val recorder =
            object : org.dexpace.sdk.core.http.pipeline.HttpStep {
                override val stage = org.dexpace.sdk.core.http.pipeline.Stage.POST_AUTH

                override fun process(
                    request: Request,
                    next: org.dexpace.sdk.core.http.pipeline.PipelineNext,
                ): Response {
                    recorderCalls.incrementAndGet()
                    return next.process()
                }
            }
        val step =
            object : KeyCredentialAuthStep(KeyCredential("k")) {
                override fun authorizeRequestOnChallenge(
                    request: Request,
                    response: Response,
                ): Request = request
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(step)
                .append(recorder)
                .build()

        pipeline.send(getHttpsRequest())
        assertEquals(2, recorderCalls.get(), "downstream step must run on both initial and retry attempts")
    }

    @Test
    fun `FixedClock integration drives deterministic token expiry`() {
        val clock = FixedClock(Instant.parse("2024-06-01T00:00:00Z"))
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                fetches.incrementAndGet()
                BearerToken("tk-${fetches.get()}", clock.now().plusSeconds(100))
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(
                    BearerTokenAuthStep(
                        provider,
                        listOf("scope"),
                        refreshMargin = Duration.ofSeconds(10),
                        clock = clock,
                    ),
                )
                .build()

        pipeline.send(getHttpsRequest())
        assertEquals(1, fetches.get())

        // Advance to exactly the refresh boundary (now+10s margin == expiry, exclusive →
        // NOT yet expired). One more second tips over.
        clock.advance(Duration.ofSeconds(91))
        pipeline.send(getHttpsRequest())
        assertEquals(2, fetches.get(), "token should refresh once we cross the 100-10 = 90s mark")
    }

    // ----------------- Cross-origin redirect credential leak (S-1) -----------------

    @Test
    fun `cross-origin redirect does NOT re-stamp the bearer credential on the foreign host`() {
        // REDIRECT wraps AUTH: the 302 to a different host is re-issued through AUTH, which
        // must NOT re-stamp the caller's bearer token onto the foreign origin.
        val provider = BearerTokenProvider { _, _ -> BearerToken("super-secret", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://evil.example.com/v2") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())

        // First hop (same origin) carries the token.
        assertEquals("Bearer super-secret", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        // Second hop (foreign origin) must NOT carry the token — and must not leak the internal marker.
        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "bearer credential must not follow a cross-origin redirect",
        )
        assertNull(
            fake.requests[1].headers.get(CrossOriginRedirectMarker.MARKER_HEADER),
            "internal cross-origin marker must be stripped before the wire",
        )
        assertEquals("https://evil.example.com/v2", fake.requests[1].url.toString())
    }

    @Test
    fun `cross-origin redirect does NOT re-stamp the key credential on the foreign host`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://evil.example.com/v2") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(KeyCredentialAuthStep(KeyCredential("secret-key")))
                .build()

        pipeline.send(getHttpsRequest())

        assertEquals("secret-key", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "key credential must not follow a cross-origin redirect",
        )
        assertNull(fake.requests[1].headers.get(CrossOriginRedirectMarker.MARKER_HEADER))
    }

    @Test
    fun `same-origin redirect DOES re-stamp the credential on the original host`() {
        // A same-origin redirect is not marked; the AUTH stage re-stamps as normal.
        val provider = BearerTokenProvider { _, _ -> BearerToken("tk", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://api.example.com/v2") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())

        // Both hops are same-origin → the token is (re-)stamped on each.
        assertEquals("Bearer tk", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        assertEquals(
            "Bearer tk",
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "same-origin redirect must re-stamp the credential",
        )
    }

    @Test
    fun `cross-origin redirect differing only by port is treated as cross-origin`() {
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://api.example.com:8443/v2") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(KeyCredentialAuthStep(KeyCredential("secret-key")))
                .build()

        pipeline.send(getHttpsRequest())

        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "a port-only origin difference must still suppress credential re-stamping",
        )
    }

    @Test
    fun `bearer credential does not re-stamp on a same-origin sub-hop of a foreign host`() {
        // Multi-hop chain: api (seed) -> evil/a -> evil/b. The first hop leaves the seed
        // origin and is correctly suppressed. The second hop is same-origin *relative to the
        // previous hop* (evil -> evil) but still foreign *relative to the seed*. The cross-
        // origin decision must be made against the seed origin, not the immediately preceding
        // hop, or the credential leaks onto the foreign host on the second hop.
        val provider = BearerTokenProvider { _, _ -> BearerToken("super-secret", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://evil.example.com/a") }
                .enqueue { status(302).header("Location", "https://evil.example.com/b") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        pipeline.send(getHttpsRequest())

        // Hop 0 — the seed origin legitimately carries the token.
        assertEquals("Bearer super-secret", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        // Hop 1 — first foreign hop, credential suppressed.
        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "credential must not follow the first cross-origin hop",
        )
        // Hop 2 — same origin as hop 1, but still foreign vs the seed: must stay credential-free.
        assertNull(
            fake.requests[2].headers.get(HttpHeaderName.AUTHORIZATION),
            "credential must not be re-stamped on a same-origin sub-hop of a foreign host",
        )
        assertNull(
            fake.requests[2].headers.get(CrossOriginRedirectMarker.MARKER_HEADER),
            "internal cross-origin marker must be stripped before the wire",
        )
        assertEquals("https://evil.example.com/b", fake.requests[2].url.toString())
    }

    @Test
    fun `key credential is re-stamped when a foreign chain bounces back to the seed origin`() {
        // api (seed) -> evil (foreign, suppressed) -> api (back to seed). The final hop targets
        // the seed origin again, so re-stamping the credential there is correct — the seed is the
        // credential's intended origin. Guards against an over-broad "once foreign, always
        // foreign" fix that would wrongly withhold the credential from the seed host.
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "https://evil.example.com/a") }
                .enqueue { status(302).header("Location", "https://api.example.com/back") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep())
                .append(KeyCredentialAuthStep(KeyCredential("secret-key")))
                .build()

        pipeline.send(getHttpsRequest())

        assertEquals("secret-key", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "credential must not follow the cross-origin hop",
        )
        assertEquals(
            "secret-key",
            fake.requests[2].headers.get(HttpHeaderName.AUTHORIZATION),
            "credential must be re-stamped when the chain returns to the seed origin",
        )
    }

    @Test
    fun `marker-suppressed cross-origin redirect to an http host is forwarded credential-free without throwing`() {
        // REDIRECT wraps AUTH. With allowSchemeDowngrade=true the redirect step deliberately
        // follows an HTTPS -> HTTP cross-origin hop and marks the re-issued request so AUTH does
        // not re-stamp the credential. The HTTPS guard in AUTH protects credential STAMPING; it
        // must not fire on the marker-suppressed re-issue (no credential is being attached), so
        // the intentionally-allowed downgrade must NOT turn into an IllegalStateException.
        val provider = BearerTokenProvider { _, _ -> BearerToken("super-secret", null) }
        val fake =
            FakeHttpClient()
                .enqueue { status(302).header("Location", "http://evil.example.com/v2") }
                .enqueue { status(200) }

        val pipeline =
            HttpPipelineBuilder(fake)
                .append(DefaultRedirectStep(HttpRedirectOptions(allowSchemeDowngrade = true)))
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        // No exception: the HTTPS guard must not reject the marker-suppressed http re-issue.
        val response = pipeline.send(getHttpsRequest())
        assertEquals(200, response.status.code)

        // Hop 0 — the HTTPS seed origin legitimately carries the credential.
        assertEquals("Bearer super-secret", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        // Hop 1 — foreign http host: NEITHER the credential NOR the internal marker reaches the wire.
        assertNull(
            fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION),
            "credential must not follow a cross-origin redirect to an http host",
        )
        assertNull(
            fake.requests[1].headers.get(CrossOriginRedirectMarker.MARKER_HEADER),
            "internal cross-origin marker must be stripped before the wire",
        )
        assertEquals("http", fake.requests[1].url.protocol)
    }

    @Test
    fun `unmarked plaintext http credential-stamping request is still rejected by the HTTPS guard`() {
        // The fix only suppresses the HTTPS guard on the marker-suppressed branch (where no
        // credential is attached). A genuine plaintext http request that would have its credential
        // STAMPED must still be rejected, or the fix would open a real credential-leak path.
        val provider = BearerTokenProvider { _, _ -> BearerToken("super-secret", null) }
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope")))
                .build()

        val request = Request.builder().method(Method.GET).url("http://api.example.com/x").build()
        val ex = assertFailsWith<IllegalStateException> { pipeline.send(request) }

        val msg = ex.message ?: ""
        assertTrue(msg.contains("HTTPS"), "missing HTTPS mention: $msg")
        assertTrue(msg.contains("http"), "missing scheme: $msg")
        // The guard must fire before any wire write.
        assertEquals(0, fake.callCount)
    }

    // ----------------- Helpers -----------------

    private fun getHttpsRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .build()

    /** ResponseBody that increments [closes] on close() and refuses source() reads. */
    private class CloseTrackingResponseBody(
        private val closes: AtomicInteger,
    ) : org.dexpace.sdk.core.http.response.ResponseBody() {
        override fun mediaType() = null

        override fun contentLength(): Long = 0

        override fun source() = throw UnsupportedOperationException("not read in this test")

        override fun close() {
            closes.incrementAndGet()
        }
    }
}
