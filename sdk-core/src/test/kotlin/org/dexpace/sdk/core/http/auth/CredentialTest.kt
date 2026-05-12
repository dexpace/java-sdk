package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.common.HttpHeaderName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
}
