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

class ChallengeHandlerTest {
    /**
     * A handler that overrides ONLY the four-argument `handleChallenges` so the
     * three-argument default-method overload exercises the interface's delegating
     * implementation. This is what wires up coverage on `ChallengeHandler.handleChallenges`
     * (3-arg) and `ChallengeHandler$DefaultImpls`.
     */
    private class CapturingHandler(
        private val acceptable: Boolean = true,
    ) : ChallengeHandler {
        var lastIsProxy: Boolean? = null
            private set

        override fun handleChallenges(
            method: Method,
            uri: URI,
            challenges: List<AuthenticateChallenge>,
            isProxy: Boolean,
        ): AuthorizationHeader? {
            lastIsProxy = isProxy
            return if (acceptable) AuthorizationHeader("X-Header", "v") else null
        }

        override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean = acceptable
    }

    @Test
    fun `three-arg overload delegates with isProxy=false`() {
        val handler = CapturingHandler()
        val result = handler.handleChallenges(Method.GET, URI.create("https://api"), emptyList())
        assertNotNull(result)
        assertEquals("X-Header", result.name)
        assertEquals(false, handler.lastIsProxy)
    }

    @Test
    fun `three-arg overload returns null when the underlying handler declines`() {
        val handler = CapturingHandler(acceptable = false)
        assertNull(handler.handleChallenges(Method.GET, URI.create("https://api"), emptyList()))
        // The delegating overload must have called through (so isProxy is still recorded).
        assertEquals(false, handler.lastIsProxy)
    }

    @Test
    fun `four-arg overload with explicit isProxy=true reaches the handler`() {
        val handler = CapturingHandler()
        handler.handleChallenges(Method.GET, URI.create("https://api"), emptyList(), isProxy = true)
        assertEquals(true, handler.lastIsProxy)
    }

    @Test
    fun `of with no handlers returns a composite that declines everything`() {
        val composite = ChallengeHandler.of()
        assertFalse(composite.canHandle(emptyList()))
        assertNull(composite.handleChallenges(Method.GET, URI.create("https://api"), emptyList()))
    }

    @Test
    fun `of accepts varargs and uses first matching handler`() {
        val a = CapturingHandler(acceptable = false)
        val b = CapturingHandler(acceptable = true)
        val composite = ChallengeHandler.of(a, b)
        assertTrue(composite.canHandle(emptyList()))
    }
}
