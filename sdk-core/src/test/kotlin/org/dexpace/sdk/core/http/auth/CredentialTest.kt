/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.common.HttpHeaderName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialTest {
    // ----------------- KeyCredential -----------------

    @Test
    fun `KeyCredential defaults to Authorization header and no prefix`() {
        val cred = KeyCredential("secret-key")
        assertEquals("secret-key", cred.apiKey)
        assertEquals(HttpHeaderName.AUTHORIZATION, cred.headerName)
        assertNull(cred.prefix)
    }

    @Test
    fun `KeyCredential accepts a custom header name`() {
        val cred = KeyCredential("secret-key", headerName = HttpHeaderName.fromString("X-API-Key"))
        assertEquals(HttpHeaderName.fromString("X-API-Key"), cred.headerName)
    }

    @Test
    fun `KeyCredential preserves a non-null prefix`() {
        val cred = KeyCredential("secret-key", prefix = "SharedAccessKey")
        assertEquals("SharedAccessKey", cred.prefix)
    }

    @Test
    fun `KeyCredential rejects an empty apiKey`() {
        val ex = assertFailsWith<IllegalArgumentException> { KeyCredential("") }
        assertEquals("apiKey must not be blank", ex.message)
    }

    @Test
    fun `KeyCredential rejects a whitespace-only apiKey`() {
        val ex = assertFailsWith<IllegalArgumentException> { KeyCredential("   ") }
        assertEquals("apiKey must not be blank", ex.message)
    }

    @Test
    fun `KeyCredential is a Credential`() {
        val cred: Credential = KeyCredential("k")
        assertEquals("k", (cred as KeyCredential).apiKey)
    }

    @Test
    fun `KeyCredential toString redacts the apiKey but keeps non-secret fields`() {
        val cred = KeyCredential("super-secret-key", prefix = "SharedAccessKey")
        val rendered = cred.toString()
        assertFalse(rendered.contains("super-secret-key"), "toString must not contain the raw apiKey")
        assertTrue(rendered.contains("apiKey=***"), "toString must redact the apiKey")
        assertTrue(rendered.contains("SharedAccessKey"), "toString should keep the non-secret prefix for diagnostics")
        assertTrue(
            rendered.contains(HttpHeaderName.AUTHORIZATION.toString()),
            "toString should keep the non-secret headerName for diagnostics",
        )
    }

    @Test
    fun `KeyCredential toString redacts the apiKey with default fields`() {
        val cred = KeyCredential("another-secret")
        val rendered = cred.toString()
        assertFalse(rendered.contains("another-secret"), "toString must not contain the raw apiKey")
        assertTrue(rendered.contains("apiKey=***"), "toString must redact the apiKey")
    }

    // ----------------- NamedKeyCredential -----------------

    @Test
    fun `NamedKeyCredential round-trips name and key`() {
        val cred = NamedKeyCredential("acct", "secret")
        assertEquals("acct", cred.name)
        assertEquals("secret", cred.key)
    }

    @Test
    fun `NamedKeyCredential rejects empty name`() {
        val ex = assertFailsWith<IllegalArgumentException> { NamedKeyCredential("", "secret") }
        assertEquals("name must not be blank", ex.message)
    }

    @Test
    fun `NamedKeyCredential rejects whitespace-only name`() {
        val ex = assertFailsWith<IllegalArgumentException> { NamedKeyCredential("   ", "secret") }
        assertEquals("name must not be blank", ex.message)
    }

    @Test
    fun `NamedKeyCredential rejects empty key`() {
        val ex = assertFailsWith<IllegalArgumentException> { NamedKeyCredential("acct", "") }
        assertEquals("key must not be blank", ex.message)
    }

    @Test
    fun `NamedKeyCredential rejects whitespace-only key`() {
        val ex = assertFailsWith<IllegalArgumentException> { NamedKeyCredential("acct", "   ") }
        assertEquals("key must not be blank", ex.message)
    }

    @Test
    fun `NamedKeyCredential is a Credential`() {
        val cred: Credential = NamedKeyCredential("acct", "secret")
        assertEquals("acct", (cred as NamedKeyCredential).name)
    }

    @Test
    fun `NamedKeyCredential toString redacts the key but keeps the name`() {
        val cred = NamedKeyCredential("acct-name", "super-secret-key")
        val rendered = cred.toString()
        assertFalse(rendered.contains("super-secret-key"), "toString must not contain the raw key")
        assertTrue(rendered.contains("key=***"), "toString must redact the key")
        assertTrue(rendered.contains("acct-name"), "toString should keep the non-secret name for diagnostics")
    }

    @Test
    fun `NamedKeyCredential keeps identity equality - redaction is toString-only`() {
        // These are reference types (not data classes): equality is identity-based and the
        // redacting toString override must not change that. Same instance equals itself;
        // distinct instances with the same fields are not equal.
        val cred = NamedKeyCredential("acct", "secret")
        assertEquals(cred, cred)
        assertNotEquals<NamedKeyCredential>(cred, NamedKeyCredential("acct", "secret"))
    }

    @Test
    fun `KeyCredential keeps identity equality - redaction is toString-only`() {
        val cred = KeyCredential("secret-key")
        assertEquals(cred, cred)
        assertNotEquals<KeyCredential>(cred, KeyCredential("secret-key"))
    }
}
