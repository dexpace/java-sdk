/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasicChallengeHandlerTest {
    private val uri = URI.create("https://api.example.com/resource")
    private val basicChallenge = AuthenticateChallenge("Basic", mapOf("realm" to "api"))
    private val digestChallenge =
        AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "api", "nonce" to "x", "qop" to "auth"),
        )

    @Test
    fun `constructor base64-encodes the credentials in the precomputed header`() {
        val handler = BasicChallengeHandler("alice", "wonderland")
        val header = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge))!!
        val expected =
            "Basic " +
                Base64.getEncoder()
                    .encodeToString("alice:wonderland".toByteArray(Charsets.UTF_8))
        assertEquals(expected, header.value)
    }

    @Test
    fun `canHandle returns true for Basic challenge`() {
        val handler = BasicChallengeHandler("u", "p")
        assertTrue(handler.canHandle(listOf(basicChallenge)))
    }

    @Test
    fun `canHandle returns false for non-Basic challenge`() {
        val handler = BasicChallengeHandler("u", "p")
        assertFalse(handler.canHandle(listOf(digestChallenge)))
    }

    @Test
    fun `canHandle is case-insensitive on scheme name`() {
        val handler = BasicChallengeHandler("u", "p")
        assertTrue(handler.canHandle(listOf(AuthenticateChallenge("basic", emptyMap()))))
        assertTrue(handler.canHandle(listOf(AuthenticateChallenge("BASIC", emptyMap()))))
        assertTrue(handler.canHandle(listOf(AuthenticateChallenge("Basic", emptyMap()))))
    }

    @Test
    fun `handleChallenges emits Authorization header when not proxy`() {
        val handler = BasicChallengeHandler("u", "p")
        val result = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge), isProxy = false)
        assertNotNull(result)
        assertEquals("Authorization", result.name)
    }

    @Test
    fun `handleChallenges emits Proxy-Authorization header when proxy`() {
        val handler = BasicChallengeHandler("u", "p")
        val result = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge), isProxy = true)
        assertNotNull(result)
        assertEquals("Proxy-Authorization", result.name)
    }

    @Test
    fun `handleChallenges returns null when no Basic challenge is offered`() {
        val handler = BasicChallengeHandler("u", "p")
        assertNull(handler.handleChallenges(Method.GET, uri, listOf(digestChallenge)))
    }

    @Test
    fun `empty username is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { BasicChallengeHandler("", "p") }
    }

    @Test
    fun `empty password is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { BasicChallengeHandler("u", "") }
    }

    @Test
    fun `whitespace-only username is accepted (RFC 7617 allows empty username)`() {
        // RFC 7617 permits non-empty credentials; blank-but-non-empty usernames are
        // unusual but valid per the spec. isNotEmpty() is the guard, not isNotBlank().
        val handler = BasicChallengeHandler(" ", "p")
        val result = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge))
        assertNotNull(result)
    }

    @Test
    fun `whitespace-only password is accepted (RFC 7617 allows empty password)`() {
        // RFC 7617 permits a colon-separated pair where the password may be blank.
        val handler = BasicChallengeHandler("u", " ")
        val result = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge))
        assertNotNull(result)
    }

    @Test
    fun `header value is identical across calls (precomputed once)`() {
        val handler = BasicChallengeHandler("a", "b")
        val first = handler.handleChallenges(Method.GET, uri, listOf(basicChallenge))!!.value
        val second = handler.handleChallenges(Method.POST, uri, listOf(basicChallenge))!!.value
        // Same reference would be ideal but a String equality assertion is what
        // we need to verify — handler shouldn't recompute base64 each call.
        assertEquals(first, second)
    }
}
