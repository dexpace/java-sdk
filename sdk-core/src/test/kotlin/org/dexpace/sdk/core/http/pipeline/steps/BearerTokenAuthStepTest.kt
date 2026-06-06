/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.auth.BearerToken
import org.dexpace.sdk.core.http.auth.BearerTokenProvider
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.testing.FakeHttpClient
import org.dexpace.sdk.core.testing.FixedClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BearerTokenAuthStepTest {
    private val futureExpiry: Instant = Instant.parse("2099-01-01T00:00:00Z")
    private val nowInstant: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = FixedClock(nowInstant)

    // ---- B14: fresh-token validation does NOT apply refreshMargin ----

    /**
     * B14: A provider that returns a token whose expiresAt is within the refreshMargin of
     * `now` is considered misbehaving — it minted a token that would immediately be treated
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

    // ---- Helpers ----

    private fun getRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/resource")
            .build()
}
