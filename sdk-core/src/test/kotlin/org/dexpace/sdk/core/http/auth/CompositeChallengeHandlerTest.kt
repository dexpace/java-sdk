/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositeChallengeHandlerTest {
    private val uri = URI.create("https://api.example.com/r")

    /** A fake handler that always claims to handle and emits a constant value. */
    private class FakeHandler(
        private val label: String,
        private val accept: (List<AuthenticateChallenge>) -> Boolean = { true },
    ) : ChallengeHandler {
        var invocations = 0
            private set

        override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean = accept(challenges)

        override fun handleChallenges(
            method: Method,
            uri: URI,
            challenges: List<AuthenticateChallenge>,
            isProxy: Boolean,
        ): AuthorizationHeader? {
            if (!canHandle(challenges)) return null
            invocations++
            return AuthorizationHeader("X-Test", label)
        }
    }

    @Test
    fun `first-handler-that-can-handle wins (order matters)`() {
        val first = FakeHandler("first")
        val second = FakeHandler("second")
        val composite = ChallengeHandler.of(first, second)
        val result = composite.handleChallenges(Method.GET, uri, emptyList())!!
        assertEquals("first", result.value)
        assertEquals(1, first.invocations)
        assertEquals(0, second.invocations)
    }

    @Test
    fun `second handler invoked when first declines`() {
        val first = FakeHandler("first", accept = { false })
        val second = FakeHandler("second")
        val composite = ChallengeHandler.of(first, second)
        val result = composite.handleChallenges(Method.GET, uri, emptyList())!!
        assertEquals("second", result.value)
        assertEquals(0, first.invocations)
        assertEquals(1, second.invocations)
    }

    @Test
    fun `returns null when no handler can handle`() {
        val a = FakeHandler("a", accept = { false })
        val b = FakeHandler("b", accept = { false })
        val composite = ChallengeHandler.of(a, b)
        assertNull(composite.handleChallenges(Method.GET, uri, emptyList()))
    }

    @Test
    fun `canHandle returns true when any handler can handle`() {
        val a = FakeHandler("a", accept = { false })
        val b = FakeHandler("b", accept = { true })
        val composite = ChallengeHandler.of(a, b)
        assertTrue(composite.canHandle(emptyList()))
    }

    @Test
    fun `canHandle returns false when no handler can handle`() {
        val a = FakeHandler("a", accept = { false })
        val b = FakeHandler("b", accept = { false })
        val composite = ChallengeHandler.of(a, b)
        assertFalse(composite.canHandle(emptyList()))
    }

    @Test
    fun `Digest-before-Basic ordering picks Digest when both offered`() {
        val challenges =
            listOf(
                AuthenticateChallenge("Basic", mapOf("realm" to "r")),
                AuthenticateChallenge(
                    "Digest",
                    mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5"),
                ),
            )
        val basic = BasicChallengeHandler("u", "p")
        val digest = DigestChallengeHandler("u", "p")
        val composite = ChallengeHandler.of(digest, basic)
        val result = composite.handleChallenges(Method.GET, uri, challenges)
        assertNotNull(result)
        assertTrue(result.value.startsWith("Digest "), "expected Digest header but got: ${result.value}")
    }

    @Test
    fun `Basic-before-Digest ordering picks Basic when both offered`() {
        // Confirms order matters — this is the anti-test for the canonical recipe.
        val challenges =
            listOf(
                AuthenticateChallenge("Basic", mapOf("realm" to "r")),
                AuthenticateChallenge(
                    "Digest",
                    mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5"),
                ),
            )
        val basic = BasicChallengeHandler("u", "p")
        val digest = DigestChallengeHandler("u", "p")
        val composite = ChallengeHandler.of(basic, digest)
        val result = composite.handleChallenges(Method.GET, uri, challenges)
        assertNotNull(result)
        assertTrue(result.value.startsWith("Basic "), "expected Basic header but got: ${result.value}")
    }
}
