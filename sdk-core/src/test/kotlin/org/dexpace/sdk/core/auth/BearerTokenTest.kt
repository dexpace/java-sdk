/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BearerTokenTest {
    @Test
    fun `data class round-trips token and expiresAt for equality`() {
        val expiry = Instant.parse("2030-01-01T00:00:00Z")
        val a = BearerToken("tk", expiry)
        val b = BearerToken("tk", expiry)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on token mismatch`() {
        val expiry = Instant.parse("2030-01-01T00:00:00Z")
        assertNotEquals(BearerToken("a", expiry), BearerToken("b", expiry))
    }

    @Test
    fun `isExpiredAt returns false when expiresAt is null - long-lived token`() {
        val token = BearerToken("tk", null)
        assertFalse(token.isExpiredAt(Instant.parse("2099-12-31T23:59:59Z")))
    }

    @Test
    fun `isExpiredAt returns true when now is strictly after expiresAt`() {
        val expiry = Instant.parse("2024-01-01T00:00:00Z")
        val token = BearerToken("tk", expiry)
        assertTrue(token.isExpiredAt(expiry.plusSeconds(1)))
    }

    @Test
    fun `isExpiredAt returns false when now equals expiresAt - exclusive boundary`() {
        val expiry = Instant.parse("2024-01-01T00:00:00Z")
        val token = BearerToken("tk", expiry)
        // `isExpiredAt` uses `isAfter` (strictly greater), so `now == expiresAt` is NOT expired.
        assertFalse(token.isExpiredAt(expiry))
    }

    @Test
    fun `isExpiredAt with marginBefore considers the token expired ahead of actual expiry`() {
        val expiry = Instant.parse("2024-01-01T00:00:00Z")
        val token = BearerToken("tk", expiry)
        // 10s before expiry, with a 10s margin → considered expired (10s + 0 advance > expiry-10s offset).
        val tenSecondsBefore = expiry.minusSeconds(10)
        assertTrue(token.isExpiredAt(tenSecondsBefore, Duration.ofSeconds(10).plusMillis(1)))
        // With margin equal to 10s, now+10s == expiry, which is the exclusive boundary → NOT expired.
        assertFalse(token.isExpiredAt(tenSecondsBefore, Duration.ofSeconds(10)))
    }

    @Test
    fun `empty token is rejected at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> { BearerToken("", Instant.now()) }
        assertEquals("token must not be blank", ex.message)
    }

    @Test
    fun `whitespace-only token is rejected at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> { BearerToken("   ", Instant.now()) }
        assertEquals("token must not be blank", ex.message)
    }

    @Test
    fun `BearerToken is a Credential`() {
        val cred: Credential = BearerToken("tk", null)
        assertEquals("tk", (cred as BearerToken).token)
    }

    @Test
    fun `toString redacts the token but keeps expiresAt`() {
        // S-4: the secret token must never reach a log line, exception, or debugger via
        // the default data-class toString.
        val expiry = Instant.parse("2030-01-01T00:00:00Z")
        val token = BearerToken("super-secret-value", expiry)
        val rendered = token.toString()
        assertFalse(rendered.contains("super-secret-value"), "toString must not contain the raw token")
        assertTrue(rendered.contains("token=***"), "toString must redact the token")
        assertTrue(rendered.contains(expiry.toString()), "toString should keep expiresAt for diagnostics")
    }

    @Test
    fun `toString redacts the token for a non-expiring token`() {
        val token = BearerToken("another-secret", null)
        val rendered = token.toString()
        assertFalse(rendered.contains("another-secret"), "toString must not contain the raw token")
        assertEquals("BearerToken(token=***, expiresAt=null)", rendered)
    }

    @Test
    fun `redacted toString does not affect equality or hashCode`() {
        // S-4: redaction is a toString-only change; value semantics are preserved.
        val a = BearerToken("tk", null)
        val b = BearerToken("tk", null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(BearerToken("tk", null), BearerToken("other", null))
    }
}
