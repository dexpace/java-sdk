/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.auth.BearerToken
import org.dexpace.sdk.core.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BearerTokenAuthStepTest {
    private val futureExpiry: Instant = Instant.parse("2099-01-01T00:00:00Z")
    private val nowInstant: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = FixedClock(nowInstant)

    @BeforeTest
    fun setUp() {
        // The 401-challenge retry path closes the 401 response body; tests that exercise it
        // need an installed IoProvider so body materialization succeeds.
        Io.installProvider(OkioIoProvider)
    }

    // ---- fresh-token validation does NOT apply refreshMargin ----

    /**
     * A provider that returns a token whose expiresAt is within the refreshMargin of
     * `now` is considered misbehaving — it generated a token that would immediately be treated
     * as expired on the next request. The [BearerTokenAuthStep] must throw
     * [IllegalStateException] even though the token is technically not yet expired (it is
     * just within the margin). The validation uses `isExpiredAt(now)` WITHOUT margin so that
     * a provider returning an effectively-expired token is rejected unconditionally.
     */
    @Test
    fun `provider returning token expired at now throws IllegalStateException`() {
        // Token expires exactly at `now` — expired per isExpiredAt(now) since the check is
        // `now.plus(ZERO).isAfter(expiresAt)` which is `now.isAfter(now)` = false. To make
        // the token actually expired we place expiry 1 second in the past relative to `now`.
        val expiredToken = BearerToken("expired-token", nowInstant.minusSeconds(1))
        val provider = BearerTokenProvider { _, _ -> expiredToken }

        val step =
            BearerTokenAuthStep(
                provider = provider,
                scopes = listOf("scope1"),
                refreshMargin = Duration.ofSeconds(30),
                clock = clock,
            )

        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake).append(step).build()

        assertFailsWith<IllegalStateException> {
            pipeline.send(getRequest())
        }
    }

    @Test
    fun `provider returning token with expiry exactly equal to now throws`() {
        // expiresAt == now: `now.isAfter(now)` == false, so isExpiredAt(now) returns false
        // BUT this is a borderline case. Let's test what happens when expiry is 1ns before now.
        val expiredToken = BearerToken("border-token", nowInstant.minusNanos(1))
        val provider = BearerTokenProvider { _, _ -> expiredToken }

        val step =
            BearerTokenAuthStep(
                provider = provider,
                scopes = listOf("scope1"),
                refreshMargin = Duration.ofSeconds(30),
                clock = clock,
            )

        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake).append(step).build()

        assertFailsWith<IllegalStateException> {
            pipeline.send(getRequest())
        }
    }

    @Test
    fun `provider returning token that would expire within refreshMargin is rejected (no margin applied)`() {
        // Token expires in 10 seconds. refreshMargin is 30 seconds.
        // On the CACHE CHECK: isExpiredAt(now, 30s) → now + 30s > expiresAt (now + 10s) → true (needs refresh).
        // After fetch: fresh-token validation uses isExpiredAt(now) WITHOUT margin.
        //   isExpiredAt(now) → now.isAfter(now + 10s) = false → NOT expired → should pass.
        // This test verifies the provider can return a token 10s from now when margin is 30s —
        // it must NOT be rejected by the fresh-check (the rejection only applies to truly expired tokens).
        val tenSecondsOut = nowInstant.plusSeconds(10)
        val tokenCloseToExpiry = BearerToken("short-lived", tenSecondsOut)
        val provider = BearerTokenProvider { _, _ -> tokenCloseToExpiry }

        val step =
            BearerTokenAuthStep(
                provider = provider,
                scopes = listOf("scope1"),
                refreshMargin = Duration.ofSeconds(30),
                clock = clock,
            )

        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake).append(step).build()

        // Should NOT throw — the token is not actually expired at now, even though it's
        // within the refresh margin. The margin only triggers re-fetch, not rejection.
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)

        // The Authorization header is stamped.
        val sentRequest = fake.requests.single()
        val auth = sentRequest.headers.get(HttpHeaderName.AUTHORIZATION)
        assertNotNull(auth)
        assertTrue(auth.startsWith("Bearer "), "Authorization must start with 'Bearer ': $auth")
    }

    @Test
    fun `valid token is stamped as Authorization header`() {
        val token = BearerToken("my-access-token", futureExpiry)
        val provider = BearerTokenProvider { _, _ -> token }

        val step = BearerTokenAuthStep(provider, listOf("openid"), clock = clock)
        val fake = FakeHttpClient().enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake).append(step).build()

        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)

        val auth = fake.requests.single().headers.get(HttpHeaderName.AUTHORIZATION)
        assertEquals("Bearer my-access-token", auth)
    }

    @Test
    fun `provider throwing exception propagates without caching`() {
        // Verifies that a provider that throws on the first call is re-invoked on the next
        // request (the exception is not cached). This exercises the exception-propagation path
        // through fetchFresh and currentToken.
        var calls = 0
        val providerThrowingOnFirstCall: BearerTokenProvider =
            object : BearerTokenProvider {
                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken {
                    calls++
                    if (calls == 1) error("provider temporarily unavailable")
                    return BearerToken("recovered-token", futureExpiry)
                }
            }
        val step = BearerTokenAuthStep(providerThrowingOnFirstCall, listOf("scope"), clock = clock)
        val fake =
            FakeHttpClient()
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline = HttpPipelineBuilder(fake).append(step).build()

        // First request: provider throws, pipeline propagates.
        assertFailsWith<IllegalStateException> {
            pipeline.send(getRequest())
        }
        assertEquals(1, calls, "provider must have been called once")

        // Second request: provider returns a valid token; pipeline must succeed.
        val response = pipeline.send(getRequest())
        assertEquals(200, response.status.code)
        assertEquals(2, calls, "provider must have been called again after prior exception")
    }

    // ---- Evict-on-401 ----

    @Test
    fun `401 challenge evicts the cached token and the retry carries a freshly fetched one`() {
        // A non-expiring token would normally be cached forever (no expiry margin ever fires).
        // The server rejects the first attempt with a 401 + WWW-Authenticate; the step must
        // evict the stale token and re-fetch so the single retry carries the fresh credential.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val response = pipeline.send(getRequest())

        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "exactly one retry after the 401")
        assertEquals(2, fetches.get(), "the 401 must trigger a re-fetch (eviction), not reuse the cached token")
        // First attempt carries the stale token; the retry carries the freshly fetched one.
        assertEquals("Bearer token-1", fake.requests[0].headers.get(HttpHeaderName.AUTHORIZATION))
        assertEquals("Bearer token-2", fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `401 eviction works for a non-idempotent POST`() {
        // The in-step challenge hook is NOT gated by retry-safety, so a POST (non-idempotent)
        // still re-authenticates after a 401. A replayable body lets the retry re-send.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val response = pipeline.send(postRequest())

        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "the POST must be retried once after re-authentication")
        assertEquals(2, fetches.get())
        assertEquals("Bearer token-2", fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `after a 401 eviction the next independent request still sees the refreshed token cached`() {
        // The retry re-fetches and re-caches; a subsequent unrelated request must reuse that
        // refreshed token rather than fetching a third time.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(200) }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        pipeline.send(getRequest()) // 401 → evict → retry with token-2
        pipeline.send(getRequest()) // reuses cached token-2

        assertEquals(2, fetches.get(), "the refreshed token is cached; no third fetch")
        assertEquals("Bearer token-2", fake.requests[2].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `repeated 401s surface the second 401 without a second retry`() {
        // The base AuthStep retries exactly once. If the freshly fetched token is ALSO rejected,
        // the second 401 is surfaced as the operation result (no infinite refresh loop), and the
        // provider is consulted once per attempt (twice total).
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val response = pipeline.send(getRequest())

        assertEquals(401, response.status.code, "the second 401 is surfaced, not retried again")
        assertEquals(2, fake.callCount)
        assertEquals(2, fetches.get())
    }

    @Test
    fun `cross-origin-marked request that 401s is surfaced unchanged without stamping a token`() {
        // A cross-origin redirect re-issue is stripped of its Authorization header and tagged
        // with the internal marker, so it reaches the foreign host with no credential. If that
        // host 401s, the challenge path must NOT stamp the caller's bearer token onto the
        // foreign host (that would leak the token cross-origin and bypass the HTTPS/cross-origin
        // suppression). The 401 must surface unchanged with no token fetch.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Bearer realm=\"x\"") }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        // Mimic DefaultRedirectStep's cross-origin re-issue: no Authorization, marker present.
        val markedRequest =
            Request.builder()
                .method(Method.GET)
                .url("https://foreign.example.org/resource")
                .addHeader(CrossOriginRedirectMarker.MARKER_HEADER, CrossOriginRedirectMarker.MARKER_VALUE)
                .build()

        val response = pipeline.send(markedRequest)

        assertEquals(401, response.status.code, "the cross-origin 401 must surface unchanged")
        assertEquals(1, fake.callCount, "no retry: the credential-free request must not be re-stamped")
        assertEquals(0, fetches.get(), "no token must be fetched for a credential-free cross-origin request")
        // The (single) request that reached the foreign host carried no Authorization header.
        assertNull(fake.requests.single().headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `401 advertising only a non-bearer challenge is surfaced unchanged without eviction or refetch`() {
        // A token refresh cannot satisfy a Basic-only challenge, so the step must NOT evict,
        // refetch, or retry: the 401 surfaces unchanged after the single initial fetch.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Basic realm=\"x\"") }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val response = pipeline.send(getRequest())

        assertEquals(401, response.status.code, "a non-bearer 401 must surface unchanged")
        assertEquals(1, fake.callCount, "no retry for a challenge a token refresh cannot satisfy")
        assertEquals(1, fetches.get(), "only the initial stamp fetched; the 401 must not trigger a refetch")
        assertEquals("Bearer token-1", fake.requests.single().headers.get(HttpHeaderName.AUTHORIZATION))
    }

    @Test
    fun `401 advertising both a non-bearer and a bearer challenge still evicts and retries`() {
        // The eviction gate matches the Bearer challenge anywhere in a multi-challenge header,
        // so a `Basic, Bearer` 401 still refreshes the token and retries.
        val fetches = AtomicInteger(0)
        val provider =
            BearerTokenProvider { _, _ ->
                BearerToken("token-${fetches.incrementAndGet()}", futureExpiry)
            }
        val fake =
            FakeHttpClient()
                .enqueue { status(401).header("WWW-Authenticate", "Basic realm=\"x\", Bearer realm=\"y\"") }
                .enqueue { status(200) }
        val pipeline =
            HttpPipelineBuilder(fake)
                .append(BearerTokenAuthStep(provider, listOf("scope"), clock = clock))
                .build()

        val response = pipeline.send(getRequest())

        assertEquals(200, response.status.code)
        assertEquals(2, fake.callCount, "the bearer challenge in the multi-challenge header triggers one retry")
        assertEquals(2, fetches.get())
        assertEquals("Bearer token-2", fake.requests[1].headers.get(HttpHeaderName.AUTHORIZATION))
    }

    // ---- Helpers ----

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/resource")
            .build()

    private fun postRequest(): Request =
        Request.builder()
            .method(Method.POST)
            .url("https://api.example.com/resource")
            // RequestBody.create(String) is replayable, so the non-idempotent retry can re-send.
            .body(RequestBody.create("payload"))
            .build()
}
