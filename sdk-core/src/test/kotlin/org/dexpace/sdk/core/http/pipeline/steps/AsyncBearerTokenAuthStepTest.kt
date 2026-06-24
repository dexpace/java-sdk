/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.auth.BearerToken
import org.dexpace.sdk.core.http.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.FakeSlf4jLogger
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.testing.FixedClock
import org.dexpace.sdk.core.util.Futures
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncBearerTokenAuthStepTest {
    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = FixedClock(now)

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    // ----------------- Basic stamping -----------------

    @Test
    fun `stamps a fresh token without blocking`() {
        val provider = CountingProvider { BearerToken("tok", now.plusSeconds(3600)) }
        val client = RecordingClient(200)
        val future = pipeline(provider, client).sendAsync(getRequest())
        val response = future.join()
        assertEquals(200, response.status.code)
        assertEquals("Bearer tok", client.lastAuth)
        assertEquals(1, provider.fetchCount)
    }

    @Test
    fun `caches the token across requests - one fetch for two sends`() {
        val provider = CountingProvider { BearerToken("tok", now.plusSeconds(3600)) }
        val client = RecordingClient(200)
        val p = pipeline(provider, client)
        p.sendAsync(getRequest()).join()
        p.sendAsync(getRequest()).join()
        assertEquals(1, provider.fetchCount)
    }

    // ----------------- Background refresh of an expiring token -----------------

    @Test
    fun `valid-but-expiring token is returned immediately and refreshed in the background`() {
        // Token expires in 10s; refresh margin is 30s → inside the margin but still valid.
        val deferred = CompletableFuture<BearerToken>()
        // Seed the cache with the expiring token by handing it back on the first (awaited) fetch.
        val seeded = CompletableFuture.completedFuture(BearerToken("old", now.plusSeconds(10)))
        val seedingProvider =
            object : BearerTokenProvider {
                val fetches = AtomicInteger(0)
                val gate = AtomicInteger(0)

                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = error("blocking fetch must not be called")

                override fun fetchAsync(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): CompletableFuture<BearerToken> {
                    fetches.incrementAndGet()
                    return if (gate.getAndIncrement() == 0) seeded else deferred
                }
            }
        val client = RecordingClient(200)
        val p = pipeline(seedingProvider, client)

        // First request: no cache → awaits the seeding fetch → stamps "old".
        p.sendAsync(getRequest()).join()
        assertEquals("Bearer old", client.lastAuth)

        // Second request: cached "old" is valid (expires in 10s) but inside the 30s margin →
        // it is returned IMMEDIATELY and stamped; the refresh is kicked off in the background and
        // is still pending (deferred not completed).
        val secondFuture = p.sendAsync(getRequest())
        val second = secondFuture.join()
        assertEquals(200, second.status.code)
        assertEquals("Bearer old", client.lastAuth, "expiring-but-valid token must be stamped without waiting")
        assertEquals(2, seedingProvider.fetches.get(), "a background refresh must have been started")
        assertFalse(deferred.isDone)

        // Complete the background refresh; the NEXT request now sees the new token.
        deferred.complete(BearerToken("new", now.plusSeconds(3600)))
        p.sendAsync(getRequest()).join()
        assertEquals("Bearer new", client.lastAuth)
    }

    // ----------------- Single-flight -----------------

    @Test
    fun `concurrent expired-token requests share one fetch`() {
        // No cached token → both requests must await; only ONE provider fetch should happen.
        val deferred = CompletableFuture<BearerToken>()
        val fetches = AtomicInteger(0)
        val provider =
            object : BearerTokenProvider {
                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = error("blocking fetch must not be called")

                override fun fetchAsync(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): CompletableFuture<BearerToken> {
                    fetches.incrementAndGet()
                    return deferred
                }
            }
        val client = RecordingClient(200)
        val p = pipeline(provider, client)

        val f1 = p.sendAsync(getRequest())
        val f2 = p.sendAsync(getRequest())
        // Both are parked on the single shared fetch.
        assertFalse(f1.isDone)
        assertFalse(f2.isDone)
        assertEquals(1, fetches.get(), "concurrent requests must coalesce onto one fetch")

        deferred.complete(BearerToken("tok", now.plusSeconds(3600)))
        assertEquals(200, f1.join().status.code)
        assertEquals(200, f2.join().status.code)
        assertEquals(1, fetches.get())
    }

    // ----------------- Provider errors -----------------

    @Test
    fun `provider failure propagates and is not cached`() {
        val fetches = AtomicInteger(0)
        val provider =
            object : BearerTokenProvider {
                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = error("blocking fetch must not be called")

                override fun fetchAsync(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): CompletableFuture<BearerToken> {
                    fetches.incrementAndGet()
                    return Futures.failed(RuntimeException("token endpoint down"))
                }
            }
        val client = RecordingClient(200)
        val p = pipeline(provider, client)

        val thrown = assertFails { p.sendAsync(getRequest()).join() }
        assertTrue(Futures.unwrap(thrown) is RuntimeException)
        // Not cached: a second request retries the fetch.
        assertFails { p.sendAsync(getRequest()).join() }
        assertEquals(2, fetches.get())
    }

    @Test
    fun `provider returning an already-expired token surfaces IllegalStateException`() {
        val provider = CountingProvider { BearerToken("stale", now.minusSeconds(1)) }
        val client = RecordingClient(200)
        val thrown = assertFails { pipeline(provider, client).sendAsync(getRequest()).join() }
        assertTrue(Futures.unwrap(thrown) is IllegalStateException)
    }

    // ----------------- HTTPS guard -----------------

    @Test
    fun `non-HTTPS request is rejected before any fetch`() {
        val provider = CountingProvider { BearerToken("tok", now.plusSeconds(3600)) }
        val client = RecordingClient(200)
        val request = Request.builder().method(Method.GET).url("http://api.example.com/x").build()
        val thrown = assertFails { pipeline(provider, client).sendAsync(request).join() }
        assertTrue(Futures.unwrap(thrown) is IllegalStateException)
        assertEquals(0, provider.fetchCount, "no token fetch on the rejected plaintext path")
    }

    // ----------------- 401 challenge eviction -----------------

    @Test
    fun `401 bearer challenge evicts the token and retries with a fresh one`() {
        var fetch = 0
        val provider =
            object : BearerTokenProvider {
                val fetches = AtomicInteger(0)

                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = error("blocking fetch must not be called")

                override fun fetchAsync(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): CompletableFuture<BearerToken> {
                    fetches.incrementAndGet()
                    fetch++
                    val value = if (fetch == 1) "first" else "second"
                    return CompletableFuture.completedFuture(BearerToken(value, now.plusSeconds(3600)))
                }
            }
        var call = 0
        val seenAuth = mutableListOf<String?>()
        val client =
            AsyncHttpClient { request ->
                call++
                seenAuth.add(request.headers.get(HttpHeaderName.AUTHORIZATION))
                val code = if (call == 1) 401 else 200
                val headers =
                    if (call == 1) {
                        Headers.Builder().add(HttpHeaderName.WWW_AUTHENTICATE.caseSensitiveName, "Bearer").build()
                    } else {
                        Headers.Builder().build()
                    }
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(code))
                        .headers(headers)
                        .build(),
                )
            }
        val response = pipeline(provider, client).sendAsync(getRequest()).join()
        assertEquals(200, response.status.code)
        assertEquals(listOf<String?>("Bearer first", "Bearer second"), seenAuth)
        assertEquals(2, provider.fetches.get())
    }

    @Test
    fun `401 without a bearer challenge surfaces unchanged`() {
        val provider = CountingProvider { BearerToken("tok", now.plusSeconds(3600)) }
        var call = 0
        val client =
            AsyncHttpClient { request ->
                call++
                CompletableFuture.completedFuture(
                    Response.builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.fromCode(401))
                        .headers(
                            Headers.Builder()
                                .add(HttpHeaderName.WWW_AUTHENTICATE.caseSensitiveName, "Basic realm=x")
                                .build(),
                        )
                        .build(),
                )
            }
        val response = pipeline(provider, client).sendAsync(getRequest()).join()
        assertEquals(401, response.status.code)
        assertEquals(1, call, "a Basic challenge must not trigger a bearer re-fetch + retry")
    }

    // ----------------- Background-refresh failure -----------------

    @Test
    fun `a failed background refresh logs once across joiners and leaves the cache usable`() {
        // Seed an expiring-but-valid token, then fail the background refresh while several requests
        // join the SAME in-flight refresh. The failure must be logged exactly once (by the launcher),
        // not once per joiner, and the cache must remain usable — a later refresh still succeeds.
        val fakeSlf4j = FakeSlf4jLogger("test.auth")
        val clientLogger = ClientLogger.forTesting(fakeSlf4j)
        val deferred = CompletableFuture<BearerToken>()
        val seeded = CompletableFuture.completedFuture(BearerToken("old", now.plusSeconds(10)))
        val fetches = AtomicInteger(0)
        val provider =
            object : BearerTokenProvider {
                override fun fetch(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): BearerToken = error("blocking fetch must not be called")

                override fun fetchAsync(
                    scopes: List<String>,
                    params: Map<String, Any>,
                ): CompletableFuture<BearerToken> =
                    when (fetches.getAndIncrement()) {
                        0 -> seeded
                        1 -> deferred
                        else -> CompletableFuture.completedFuture(BearerToken("new", now.plusSeconds(3600)))
                    }
            }
        val client = RecordingClient(200)
        val step =
            AsyncBearerTokenAuthStep(provider, listOf("scope"), Duration.ofSeconds(30), clock, clientLogger)
        val p = AsyncHttpPipelineBuilder(client).append(step).build()

        // R1 seeds "old"; R2 launches the background refresh (deferred); R3/R4 join the same one —
        // all four are stamped with the still-valid "old" token without waiting on the refresh.
        repeat(4) {
            assertEquals(200, p.sendAsync(getRequest()).join().status.code)
            assertEquals("Bearer old", client.lastAuth)
        }
        assertEquals(2, fetches.get(), "R3/R4 must join the in-flight refresh, not start new fetches")

        deferred.completeExceptionally(RuntimeException("token endpoint down"))

        val failureLogs =
            fakeSlf4j.records.count { rec ->
                rec.keyValues.any { it.key == "event" && it.value == "http.auth.background_refresh_failed" }
            }
        assertEquals(1, failureLogs, "a single failed background refresh must log once, not once per joiner")

        // Cache not poisoned: the failed refresh was not cached. The next request stamps the still
        // valid "old" token and starts a fresh refresh that succeeds, so a later request sees "new".
        p.sendAsync(getRequest()).join()
        p.sendAsync(getRequest()).join()
        assertEquals("Bearer new", client.lastAuth, "the cache recovered after the failed refresh")
        val failureLogsAfterRecovery =
            fakeSlf4j.records.count { rec ->
                rec.keyValues.any { it.key == "event" && it.value == "http.auth.background_refresh_failed" }
            }
        assertEquals(1, failureLogsAfterRecovery, "the successful follow-up refresh must not add another failure log")
    }

    // ----------------- Helpers -----------------

    private fun pipeline(
        provider: BearerTokenProvider,
        client: AsyncHttpClient,
    ) = AsyncHttpPipelineBuilder(client)
        .append(AsyncBearerTokenAuthStep(provider, listOf("scope"), Duration.ofSeconds(30), clock))
        .build()

    private fun getRequest(): Request = Request.builder().method(Method.GET).url("https://api.example.com/x").build()

    /** Provider that counts fetchAsync calls and returns [supply]'s token, completed. */
    private class CountingProvider(private val supply: () -> BearerToken) : BearerTokenProvider {
        private val fetches = AtomicInteger(0)

        val fetchCount: Int get() = fetches.get()

        override fun fetch(
            scopes: List<String>,
            params: Map<String, Any>,
        ): BearerToken = error("blocking fetch must not be called")

        override fun fetchAsync(
            scopes: List<String>,
            params: Map<String, Any>,
        ): CompletableFuture<BearerToken> {
            fetches.incrementAndGet()
            return CompletableFuture.completedFuture(supply())
        }
    }

    /** Async client returning a constant status and recording the Authorization header. */
    private class RecordingClient(private val code: Int) : AsyncHttpClient {
        @Volatile
        var lastAuth: String? = null

        override fun executeAsync(request: Request): CompletableFuture<Response> {
            lastAuth = request.headers.get(HttpHeaderName.AUTHORIZATION)
            return CompletableFuture.completedFuture(
                Response.builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .status(Status.fromCode(code))
                    .build(),
            )
        }
    }
}
